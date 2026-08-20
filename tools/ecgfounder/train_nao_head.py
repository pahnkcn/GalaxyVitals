"""Train a 3-class N/A/O head on frozen ECGFounder outputs.

Does not retrain the 90M-parameter backbone. It learns how to read the
150 hospital labels as CinC-style N/A/O, which is where the last bake-off
collapsed (Other recall ~1%).

The 600-record comparison split (seed=17, 200/class) is held out as test.
Training uses other 2017 public records only. Hyperparameters are selected
on a record-disjoint validation subset of that training data.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, f1_score, roc_auc_score
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from compare_nao_founder import (
    FOUNDER_LABELS,
    NAO_LABELS,
    ROOT,
    FounderRunner,
    find_records,
    load_record,
    map_founder,
    pick_subset,
    watch_like,
)

TEST_SEED = 17
TEST_PER_CLASS = 200
TRAIN_SEED = 99
VALIDATION_SEED = 23
VALIDATION_FRACTION = 0.2
CACHE_SCHEMA_VERSION = 2
CACHE_KEYS = ("x_train", "y_train", "x_test", "y_test", "x_watch", "y_watch")
CACHE_MANIFEST_KEY = "manifest_json"


def file_identity(path: Path, *, hash_contents: bool) -> dict:
    resolved = path.resolve(strict=True)
    stat = resolved.stat()
    identity = {
        "path": str(resolved),
        "size": stat.st_size,
        "mtime_ns": stat.st_mtime_ns,
    }
    if hash_contents:
        digest = hashlib.sha256()
        with resolved.open("rb") as src:
            for chunk in iter(lambda: src.read(1024 * 1024), b""):
                digest.update(chunk)
        identity["sha256"] = digest.hexdigest()
    return identity


def record_identities(rows: list) -> list[dict]:
    return [
        {
            "id": rec,
            "label": label,
            "source": file_identity(path, hash_contents=False),
        }
        for rec, path, label in rows
    ]


def build_cache_manifest(
    data_root: Path,
    founder: Path,
    train_per_class: int,
    train_rows: list,
    test_rows: list,
) -> dict:
    labels_digest = hashlib.sha256(
        json.dumps(FOUNDER_LABELS, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    return {
        "schema": CACHE_SCHEMA_VERSION,
        "data_root": str(data_root.resolve(strict=True)),
        "founder": file_identity(founder, hash_contents=True),
        "args": {
            "train_per_class": train_per_class,
            "train_seed": TRAIN_SEED,
            "test_per_class": TEST_PER_CLASS,
            "test_seed": TEST_SEED,
            "clean_train_seed": 3,
            "watch_train_seed": 5,
            "watch_test_seed": TEST_SEED,
            "founder_labels_sha256": labels_digest,
        },
        "train_records": record_identities(train_rows),
        "test_records": record_identities(test_rows),
    }


def load_feature_cache(path: Path, expected_manifest: dict) -> tuple[np.ndarray, ...]:
    with np.load(path, allow_pickle=False) as pack:
        missing = [key for key in (*CACHE_KEYS, CACHE_MANIFEST_KEY) if key not in pack.files]
        if missing:
            raise ValueError(f"feature cache is missing arrays: {missing}")

        raw_manifest = pack[CACHE_MANIFEST_KEY]
        if raw_manifest.shape != () or raw_manifest.dtype.kind != "U":
            raise ValueError("feature cache manifest must be a Unicode scalar")
        try:
            actual_manifest = json.loads(str(raw_manifest.item()))
        except (TypeError, json.JSONDecodeError) as exc:
            raise ValueError("feature cache manifest is invalid JSON") from exc
        if actual_manifest != expected_manifest:
            raise ValueError("feature cache provenance does not match current inputs")

        arrays = {key: pack[key].copy() for key in CACHE_KEYS}

    for key, value in arrays.items():
        if not np.issubdtype(value.dtype, np.number):
            raise ValueError(f"feature cache {key} must be numeric, got {value.dtype}")
        if not np.isfinite(value).all():
            raise ValueError(f"feature cache {key} contains non-finite values")

    for x_key, y_key in (("x_train", "y_train"), ("x_test", "y_test"), ("x_watch", "y_watch")):
        x = arrays[x_key]
        y = arrays[y_key]
        if x.ndim != 2 or x.shape[1] != len(FOUNDER_LABELS):
            raise ValueError(f"feature cache {x_key} must have shape (n, {len(FOUNDER_LABELS)})")
        if y.ndim != 1 or y.shape[0] != x.shape[0] or not np.issubdtype(y.dtype, np.integer):
            raise ValueError(f"feature cache {y_key} must be an integer vector matching {x_key}")
        if y.size == 0 or np.any((y < 0) | (y >= len(NAO_LABELS))):
            raise ValueError(f"feature cache {y_key} contains invalid class indices")

    expected_train = np.asarray(
        [NAO_LABELS.index(record["label"]) for record in expected_manifest["train_records"]],
        dtype=np.int64,
    )
    expected_test = np.asarray(
        [NAO_LABELS.index(record["label"]) for record in expected_manifest["test_records"]],
        dtype=np.int64,
    )
    if not np.array_equal(arrays["y_train"], np.concatenate((expected_train, expected_train))):
        raise ValueError("feature cache training rows do not match the manifest")
    if not np.array_equal(arrays["y_test"], expected_test):
        raise ValueError("feature cache clean test rows do not match the manifest")
    if not np.array_equal(arrays["y_watch"], expected_test):
        raise ValueError("feature cache watch-like test rows do not match the manifest")

    return tuple(arrays[key] for key in CACHE_KEYS)


def training_validation_indices(y_train: np.ndarray, record_count: int) -> tuple[np.ndarray, np.ndarray]:
    if y_train.shape != (record_count * 2,):
        raise ValueError("training features must contain one clean and one watch-like row per record")

    y_clean = y_train[:record_count]
    y_watch = y_train[record_count:]
    if not np.array_equal(y_clean, y_watch):
        raise ValueError("clean and watch-like training labels do not match")

    classes, counts = np.unique(y_clean, return_counts=True)
    if set(classes.tolist()) != set(range(len(NAO_LABELS))) or np.any(counts < 2):
        raise ValueError("training data needs at least two records from every N/A/O class")

    validation_count = max(len(classes), int(round(record_count * VALIDATION_FRACTION)))
    validation_count = min(validation_count, record_count - len(classes))
    record_indices = np.arange(record_count)
    fit_records, validation_records = train_test_split(
        record_indices,
        test_size=validation_count,
        random_state=VALIDATION_SEED,
        stratify=y_clean,
    )
    fit_indices = np.concatenate((fit_records, fit_records + record_count))
    validation_indices = np.concatenate(
        (validation_records, validation_records + record_count)
    )
    return fit_indices, validation_indices


def build_classifier(c: float) -> Pipeline:
    return Pipeline(
        [
            ("scaler", StandardScaler()),
            (
                "lr",
                LogisticRegression(
                    max_iter=400,
                    class_weight="balanced",
                    C=c,
                    random_state=VALIDATION_SEED,
                ),
            ),
        ]
    )


def extract_probs(runner: FounderRunner, rows: list, watch: bool, seed: int) -> tuple[np.ndarray, np.ndarray, list[str]]:
    rng = np.random.default_rng(seed)
    xs = []
    ys = []
    ids = []
    for i, (rec, mat, lab) in enumerate(rows, 1):
        x = load_record(mat)
        fs = 300.0
        if watch:
            x, fs = watch_like(x, fs, rng)
        _label, probs, _paf = runner.predict(x, fs)
        xs.append(probs)
        ys.append(lab)
        ids.append(rec)
        if i % 40 == 0 or i == len(rows):
            print(f"  {i}/{len(rows)} {rec} {lab}", flush=True)
    y = np.array([NAO_LABELS.index(v) for v in ys], dtype=np.int64)
    return np.stack(xs).astype(np.float32), y, ids


def rule_scores(probs: np.ndarray) -> np.ndarray:
    pred = []
    for row in probs:
        lab, _conf, _paf = map_founder(row)
        pred.append(NAO_LABELS.index(lab))
    return np.asarray(pred, dtype=np.int64)


def score(y_true: np.ndarray, y_pred: np.ndarray, p_af: np.ndarray) -> dict:
    labels = [0, 1, 2]
    out = {
        "n": int(len(y_true)),
        "acc": float(accuracy_score(y_true, y_pred)),
        "macro_f1": float(f1_score(y_true, y_pred, labels=labels, average="macro", zero_division=0)),
    }
    f1s = f1_score(y_true, y_pred, labels=labels, average=None, zero_division=0)
    rec = []
    for i, name in enumerate(NAO_LABELS):
        support = int(np.sum(y_true == i))
        tp = int(np.sum((y_true == i) & (y_pred == i)))
        rec_i = tp / support if support else 0.0
        rec.append(rec_i)
        out[f"{name}_f1"] = float(f1s[i])
        out[f"{name}_recall"] = float(rec_i)
    y_bin = (y_true == 1).astype(int)
    if len(set(y_bin.tolist())) == 2:
        out["af_auroc"] = float(roc_auc_score(y_bin, p_af))
        out["af_f1"] = float(f1_score(y_bin, (y_pred == 1).astype(int), zero_division=0))
    return out


def fmt(title: str, m: dict) -> str:
    return (
        f"{title}: acc={m['acc']:.3f} macro-F1={m['macro_f1']:.3f} "
        f"AF-AUROC={m.get('af_auroc', float('nan')):.3f} "
        f"N/A/O F1={m['N_f1']:.3f}/{m['A_f1']:.3f}/{m['O_f1']:.3f} "
        f"O-recall={m['O_recall']:.3f}"
    )


def p_af_from_probs(probs: np.ndarray) -> np.ndarray:
    from compare_nao_founder import AF_NAMES

    idx = [i for i, name in enumerate(FOUNDER_LABELS) if name in AF_NAMES]
    return probs[:, idx].max(axis=1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, default=ROOT / "models" / "eval" / "challenge-2017")
    parser.add_argument("--founder", type=Path, default=ROOT / "app" / "src" / "main" / "assets" / "ecg" / "ecgfounder_1lead.onnx")
    parser.add_argument("--train-per-class", type=int, default=180)
    parser.add_argument("--cache", type=Path, default=ROOT / "models" / "eval" / "founder_head")
    parser.add_argument("--out", type=Path, default=ROOT / "app" / "src" / "main" / "assets" / "ecg" / "nao_calibrator.json")
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)

    all_rows = find_records(args.data)
    test_rows = pick_subset(all_rows, TEST_PER_CLASS, TEST_SEED)
    test_ids = {r[0] for r in test_rows}
    remain = [r for r in all_rows if r[0] not in test_ids and r[2] != "~"]
    train_rows = pick_subset(remain, args.train_per_class, seed=TRAIN_SEED)
    print("train", Counter(r[2] for r in train_rows), "test", Counter(r[2] for r in test_rows))

    cache = args.cache / "features.npz"
    cache_manifest = build_cache_manifest(
        args.data,
        args.founder,
        args.train_per_class,
        train_rows,
        test_rows,
    )
    cache_loaded = False
    if cache.exists():
        try:
            x_train, y_train, x_test, y_test, x_watch, y_watch = load_feature_cache(
                cache,
                cache_manifest,
            )
            cache_loaded = True
            print("loaded cache", cache)
        except (OSError, ValueError) as exc:
            print("ignoring stale or invalid feature cache:", exc)

    if not cache_loaded:
        runner = FounderRunner(args.founder)
        print("extract train (clean)")
        x_train, y_train, train_ids = extract_probs(runner, train_rows, watch=False, seed=3)
        print("extract extra watch-aug train")
        x_aug, y_aug, augmented_ids = extract_probs(runner, train_rows, watch=True, seed=5)
        x_train = np.concatenate([x_train, x_aug], axis=0)
        y_train = np.concatenate([y_train, y_aug], axis=0)
        print("extract test clean")
        x_test, y_test, test_ids_extracted = extract_probs(
            runner,
            test_rows,
            watch=False,
            seed=TEST_SEED,
        )
        print("extract test watch-like")
        x_watch, y_watch, watch_ids = extract_probs(
            runner,
            test_rows,
            watch=True,
            seed=TEST_SEED,
        )
        expected_train_ids = [row[0] for row in train_rows]
        expected_test_ids = [row[0] for row in test_rows]
        if train_ids != expected_train_ids or augmented_ids != expected_train_ids:
            raise RuntimeError("training feature extraction changed record order")
        if test_ids_extracted != expected_test_ids or watch_ids != expected_test_ids:
            raise RuntimeError("test feature extraction changed record order")

        manifest_json = json.dumps(
            cache_manifest,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        temporary_cache = cache.with_name(f"{cache.stem}.tmp.npz")
        np.savez(
            temporary_cache,
            x_train=x_train,
            y_train=y_train,
            x_test=x_test,
            y_test=y_test,
            x_watch=x_watch,
            y_watch=y_watch,
            manifest_json=np.asarray(manifest_json),
        )
        temporary_cache.replace(cache)
        print("wrote", cache)

    baseline_clean = score(y_test, rule_scores(x_test), p_af_from_probs(x_test))
    baseline_watch = score(y_watch, rule_scores(x_watch), p_af_from_probs(x_watch))
    print(fmt("rule clean", baseline_clean))
    print(fmt("rule watch", baseline_watch))

    fit_indices, validation_indices = training_validation_indices(y_train, len(train_rows))
    best_validation = None
    best_c = None
    for c in (0.2, 1.0, 3.0, 10.0):
        candidate = build_classifier(c)
        candidate.fit(x_train[fit_indices], y_train[fit_indices])
        validation = score(
            y_train[validation_indices],
            candidate.predict(x_train[validation_indices]),
            candidate.predict_proba(x_train[validation_indices])[:, 1],
        )
        print(fmt(f"logreg C={c} validation", validation))
        if best_validation is None or validation["macro_f1"] > best_validation["macro_f1"]:
            best_validation = validation
            best_c = c

    if best_c is None or best_validation is None:
        raise RuntimeError("failed to select a logistic-regression hyperparameter")

    clf = build_classifier(best_c)
    clf.fit(x_train, y_train)
    calibrated_clean = score(
        y_test,
        clf.predict(x_test),
        clf.predict_proba(x_test)[:, 1],
    )
    calibrated_watch = score(
        y_watch,
        clf.predict(x_watch),
        clf.predict_proba(x_watch)[:, 1],
    )
    print(fmt(f"logreg C={best_c} clean test", calibrated_clean))
    print(fmt(f"logreg C={best_c} watch test", calibrated_watch))

    lr = clf.named_steps["lr"]
    scaler = clf.named_steps["scaler"]
    # Fold StandardScaler into the linear layer: W' = W / scale, b' = b - W (mean/scale)
    coef = lr.coef_.astype(np.float64) / scaler.scale_
    intercept = lr.intercept_.astype(np.float64) - (coef * scaler.mean_).sum(axis=1)
    payload = {
        "type": "logistic_nao",
        "version": 1,
        "labels": list(NAO_LABELS),
        "input": "ecgfounder_150",
        "C": best_c,
        "coef": coef.tolist(),
        "intercept": intercept.tolist(),
        "train_n": int(len(y_train)),
        "metrics": {
            "baseline_clean": baseline_clean,
            "baseline_watch": baseline_watch,
            "selection_validation": best_validation,
            "calibrated_clean": calibrated_clean,
            "calibrated_watch": calibrated_watch,
        },
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print("wrote", args.out)


if __name__ == "__main__":
    main()

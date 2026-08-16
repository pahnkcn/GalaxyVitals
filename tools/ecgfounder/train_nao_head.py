"""Train a 3-class N/A/O head on frozen ECGFounder outputs.

Does not retrain the 90M-parameter backbone. It learns how to read the
150 hospital labels as CinC-style N/A/O, which is where the last bake-off
collapsed (Other recall ~1%).

The 600-record comparison split (seed=17, 200/class) is held out as test.
Training uses other 2017 public records only.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, f1_score, roc_auc_score
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
    train_rows = pick_subset(remain, args.train_per_class, seed=99)
    print("train", Counter(r[2] for r in train_rows), "test", Counter(r[2] for r in test_rows))

    cache = args.cache / "features.npz"
    if cache.exists():
        pack = np.load(cache, allow_pickle=True)
        x_train, y_train = pack["x_train"], pack["y_train"]
        x_test, y_test = pack["x_test"], pack["y_test"]
        x_watch, y_watch = pack["x_watch"], pack["y_watch"]
        print("loaded cache", cache)
    else:
        runner = FounderRunner(args.founder)
        print("extract train (clean)")
        x_train, y_train, _ = extract_probs(runner, train_rows, watch=False, seed=3)
        print("extract extra watch-aug train")
        x_aug, y_aug, _ = extract_probs(runner, train_rows, watch=True, seed=5)
        x_train = np.concatenate([x_train, x_aug], axis=0)
        y_train = np.concatenate([y_train, y_aug], axis=0)
        print("extract test clean")
        x_test, y_test, _ = extract_probs(runner, test_rows, watch=False, seed=TEST_SEED)
        print("extract test watch-like")
        x_watch, y_watch, _ = extract_probs(runner, test_rows, watch=True, seed=TEST_SEED)
        np.savez(
            cache,
            x_train=x_train,
            y_train=y_train,
            x_test=x_test,
            y_test=y_test,
            x_watch=x_watch,
            y_watch=y_watch,
        )
        print("wrote", cache)

    baseline_clean = score(y_test, rule_scores(x_test), p_af_from_probs(x_test))
    baseline_watch = score(y_watch, rule_scores(x_watch), p_af_from_probs(x_watch))
    print(fmt("rule clean", baseline_clean))
    print(fmt("rule watch", baseline_watch))

    best = None
    best_key = None
    for c in (0.2, 1.0, 3.0, 10.0):
        clf = Pipeline(
            [
                ("scaler", StandardScaler()),
                (
                    "lr",
                    LogisticRegression(
                        max_iter=400,
                        class_weight="balanced",
                        C=c,
                    ),
                ),
            ]
        )
        clf.fit(x_train, y_train)
        pred = clf.predict(x_test)
        m = score(y_test, pred, clf.predict_proba(x_test)[:, 1])
        print(fmt(f"logreg C={c} clean", m))
        if best is None or m["macro_f1"] > best[0]["macro_f1"]:
            best = (m, clf, c)
            best_key = c

    clf = best[1]
    watch = score(y_watch, clf.predict(x_watch), clf.predict_proba(x_watch)[:, 1])
    print(fmt(f"logreg C={best_key} watch", watch))

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
        "C": best_key,
        "coef": coef.tolist(),
        "intercept": intercept.tolist(),
        "train_n": int(len(y_train)),
        "metrics": {
            "baseline_clean": baseline_clean,
            "baseline_watch": baseline_watch,
            "calibrated_clean": best[0],
            "calibrated_watch": watch,
        },
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print("wrote", args.out)


if __name__ == "__main__":
    main()

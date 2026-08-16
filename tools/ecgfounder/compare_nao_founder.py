"""Compare nao_full vs ECGFounder 1-lead on public single-lead ECGs.

Primary data: PhysioNet/CinC 2017 AliveCor (handheld, 9-60s, labels N/A/O/~).
That is the closest open set to a watch strip. Official 2017 *test* labels
are private, so we use the public training records as an external screen.

Bias notes (printed in the report):
- nao_full's I/O is the 2017 task (N/A/O, ~30s). It may have seen this set.
- ECGFounder was trained on hospital lead I and reported 2017 as external.
- The watch-like condition is therefore the fairer Galaxy Watch proxy:
  both models see the same 500 Hz degraded recording.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import sys
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
from scipy.io import loadmat
from scipy.signal import resample_poly, sosfiltfilt
from sklearn.metrics import (
    accuracy_score,
    classification_report,
    confusion_matrix,
    f1_score,
    precision_recall_fscore_support,
    roc_auc_score,
)

ROOT = Path(__file__).resolve().parents[2]
if str(Path(__file__).resolve().parent) not in sys.path:
    sys.path.insert(0, str(Path(__file__).resolve().parent))

NAO_LABELS = ("N", "A", "O")
FOUNDER_LABELS = [
    "ABNORMAL ECG",
    "NORMAL SINUS RHYTHM",
    "NORMAL ECG",
    "SINUS RHYTHM",
    "SINUS BRADYCARDIA",
    "ATRIAL FIBRILLATION",
    "SINUS TACHYCARDIA",
    "otherwise normal ecg",
    "LEFT AXIS DEVIATION",
    "PREMATURE VENTRICULAR COMPLEXES",
    "BORDERLINE ECG",
    "RIGHT BUNDLE BRANCH BLOCK",
    "SEPTAL INFARCT",
    "LEFT ATRIAL ENLARGEMENT",
    "NONSPECIFIC T WAVE ABNORMALITY",
    "LOW VOLTAGE QRS",
    "PREMATURE ATRIAL COMPLEXES",
    "ANTERIOR INFARCT",
    "INCOMPLETE RIGHT BUNDLE BRANCH BLOCK",
    "PREMATURE SUPRAVENTRICULAR COMPLEXES",
    "LEFT BUNDLE BRANCH BLOCK",
    "NONSPECIFIC T WAVE ABNORMALITY NOW EVIDENT IN",
    "NONSPECIFIC T WAVE ABNORMALITY NO LONGER EVIDENT IN",
    "T WAVE INVERSION NOW EVIDENT IN",
    "LATERAL INFARCT",
    "NONSPECIFIC ST ABNORMALITY",
    "LEFT VENTRICULAR HYPERTROPHY",
    "T WAVE INVERSION NO LONGER EVIDENT IN",
    "WITH RAPID VENTRICULAR RESPONSE",
    "QT HAS SHORTENED",
    "QT HAS LENGTHENED",
    "FUSION COMPLEXES",
    "ATRIAL FLUTTER",
    "MARKED SINUS BRADYCARDIA",
    "WITH SINUS ARRHYTHMIA",
    "NONSPECIFIC ST AND T WAVE ABNORMALITY",
    "LEFT ANTERIOR FASCICULAR BLOCK",
    "RIGHT AXIS DEVIATION",
    "ECTOPIC ATRIAL RHYTHM",
    "UNDETERMINED RHYTHM",
    "ANTEROSEPTAL INFARCT",
    "RIGHTWARD AXIS",
    "ST NOW DEPRESSED IN",
    "WITH SHORT PR",
    "WITH MARKED SINUS ARRHYTHMIA",
    "ST NO LONGER DEPRESSED IN",
    "INVERTED T WAVES HAVE REPLACED NONSPECIFIC T WAVE ABNORMALITY IN",
    "NON-SPECIFIC CHANGE IN ST SEGMENT IN",
    "NONSPECIFIC T WAVE ABNORMALITY HAS REPLACED INVERTED T WAVES IN",
    "JUNCTIONAL RHYTHM",
    "ELECTRONIC ATRIAL PACEMAKER",
    "ABERRANT CONDUCTION",
    "ELECTRONIC VENTRICULAR PACEMAKER",
    "T WAVE INVERSION LESS EVIDENT IN",
    "ANTEROLATERAL INFARCT",
    "WITH REPOLARIZATION ABNORMALITY",
    "RSR' OR QR PATTERN IN V1 SUGGESTS RIGHT VENTRICULAR CONDUCTION DELAY",
    "T WAVE INVERSION MORE EVIDENT IN",
    "WIDE QRS RHYTHM",
    "WITH PREMATURE VENTRICULAR OR ABERRANTLY CONDUCTED COMPLEXES",
    "RIGHT ATRIAL ENLARGEMENT",
    "INFERIOR INFARCT",
    "INCOMPLETE LEFT BUNDLE BRANCH BLOCK",
    "VOLTAGE CRITERIA FOR LEFT VENTRICULAR HYPERTROPHY",
    "OR DIGITALIS EFFECT",
    "BIFASCICULAR BLOCK",
    "ST NO LONGER ELEVATED IN",
    "WITH SLOW VENTRICULAR RESPONSE",
    "ST ELEVATION NOW PRESENT IN",
    "PREMATURE ECTOPIC COMPLEXES",
    "LEFT POSTERIOR FASCICULAR BLOCK",
    "T WAVE AMPLITUDE HAS DECREASED IN",
    "WITH A COMPETING JUNCTIONAL PACEMAKER",
    "RIGHT SUPERIOR AXIS DEVIATION",
    "BIATRIAL ENLARGEMENT",
    "VENTRICULAR-PACED RHYTHM",
    "ATRIAL-PACED RHYTHM",
    "T WAVE AMPLITUDE HAS INCREASED IN",
    "WITH QRS WIDENING",
    "WITH 1ST DEGREE AV BLOCK",
    "PROLONGED QT",
    "WITH PROLONGED AV CONDUCTION",
    "RIGHT VENTRICULAR HYPERTROPHY",
    "WITH QRS WIDENING AND REPOLARIZATION ABNORMALITY",
    "ATRIAL-SENSED VENTRICULAR-PACED RHYTHM",
    "AV SEQUENTIAL OR DUAL CHAMBER ELECTRONIC PACEMAKER",
    "PULMONARY DISEASE PATTERN",
    "ACUTE MI / STEMI",
    "INFERIOR-POSTERIOR INFARCT",
    "NONSPECIFIC INTRAVENTRICULAR CONDUCTION DELAY",
    "PREMATURE VENTRICULAR AND FUSION COMPLEXES",
    "IN A PATTERN OF BIGEMINY",
    "AV DUAL-PACED RHYTHM",
    "SUPRAVENTRICULAR TACHYCARDIA",
    "VENTRICULAR-PACED COMPLEXES",
    "WIDE QRS TACHYCARDIA",
    "RSR' PATTERN IN V1",
    "ST LESS DEPRESSED IN",
    "VENTRICULAR TACHYCARDIA",
    "EARLY REPOLARIZATION",
    "ST MORE DEPRESSED IN",
    "ANTEROLATERAL LEADS",
    "ELECTRONIC DEMAND PACING",
    "RBBB AND LEFT ANTERIOR FASCICULAR BLOCK",
    "LATERAL INJURY PATTERN",
    "BIVENTRICULAR PACEMAKER DETECTED",
    "SUSPECT UNSPECIFIED PACEMAKER FAILURE",
    "WOLFF-PARKINSON-WHITE",
    "WITH VENTRICULAR ESCAPE COMPLEXES",
    "INFERIOR INJURY PATTERN",
    "CONSIDER RIGHT VENTRICULAR INVOLVEMENT IN ACUTE INFERIOR INFARCT",
    "ST ELEVATION HAS REPLACED ST DEPRESSION IN",
    "NONSPECIFIC INTRAVENTRICULAR BLOCK",
    "MASKED BY FASCICULAR BLOCK",
    "PEDIATRIC ECG ANALYSIS",
    "BLOCKED",
    "WITH UNDETERMINED RHYTHM IRREGULARITY",
    "LEFTWARD AXIS",
    "WITH 2ND DEGREE SA BLOCK MOBITZ I",
    "ACUTE",
    "ABNORMAL LEFT AXIS DEVIATION",
    "WITH COMPLETE HEART BLOCK",
    "NO P-WAVES FOUND",
    "ST LESS ELEVATED IN",
    "WITH RETROGRADE CONDUCTION",
    "ST MORE ELEVATED IN",
    "JUNCTIONAL BRADYCARDIA",
    "WITH VARIABLE AV BLOCK",
    "ANTERIOR INJURY PATTERN",
    "WITH JUNCTIONAL ESCAPE COMPLEXES",
    "ACUTE MI",
    "ACUTE PERICARDITIS",
    "POSTERIOR INFARCT",
    "IDIOVENTRICULAR RHYTHM",
    "WITH 2ND DEGREE SA BLOCK MOBITZ II",
    "R IN AVL",
    "SINUS/ATRIAL CAPTURE",
    "AV DUAL-PACED COMPLEXES",
    "INFEROLATERAL INJURY PATTERN",
    "RBBB AND LEFT POSTERIOR FASCICULAR BLOCK",
    "ANTEROLATERAL INJURY PATTERN",
    "ATRIAL-PACED COMPLEXES",
    "WITH SINUS PAUSE",
    "BIVENTRICULAR HYPERTROPHY",
    "ABNORMAL RIGHT AXIS DEVIATION",
    "SUPRAVENTRICULAR COMPLEXES",
    "WITH 2ND DEGREE AV BLOCK MOBITZ I",
    "WITH 2:1 AV CONDUCTION",
    "WITH AV DISSOCIATION",
    "MULTIFOCAL ATRIAL TACHYCARDIA",
]
AF_NAMES = {
    "ATRIAL FIBRILLATION",
    "ATRIAL FLUTTER",
    "WITH RAPID VENTRICULAR RESPONSE",
    "WITH SLOW VENTRICULAR RESPONSE",
    "NO P-WAVES FOUND",
    "MULTIFOCAL ATRIAL TACHYCARDIA",
}
NORMAL_NAMES = {
    "NORMAL SINUS RHYTHM",
    "NORMAL ECG",
    "SINUS RHYTHM",
    "SINUS BRADYCARDIA",
    "SINUS TACHYCARDIA",
    "otherwise normal ecg",
    "MARKED SINUS BRADYCARDIA",
    "WITH SINUS ARRHYTHMIA",
    "WITH MARKED SINUS ARRHYTHMIA",
}
GENERIC_NAMES = {"ABNORMAL ECG", "BORDERLINE ECG"}


def resample(x: np.ndarray, fs_in: float, fs_out: float) -> np.ndarray:
    if abs(fs_in - fs_out) < 1e-6:
        return x.astype(np.float32)
    g = math.gcd(int(round(fs_in)), int(round(fs_out)))
    up = int(round(fs_out)) // g
    down = int(round(fs_in)) // g
    y = resample_poly(x.astype(np.float64), up, down)
    return y.astype(np.float32)


def zscore(x: np.ndarray) -> np.ndarray:
    mu = float(np.mean(x))
    sd = float(np.std(x))
    if sd < 1e-8:
        sd = 1.0
    return ((x - mu) / sd).astype(np.float32)


def fit_length(x: np.ndarray, n: int) -> np.ndarray:
    if x.size == n:
        return x
    out = np.zeros(n, dtype=np.float32)
    if x.size > n:
        start = max(0, (x.size - n) // 2)
        out[:] = x[start : start + n]
    else:
        start = (n - x.size) // 2
        out[start : start + x.size] = x
    return out


def load_nao_filters(path: Path) -> np.ndarray:
    obj = json.loads(path.read_text(encoding="utf-8"))
    rows = []
    for stage in obj["chain"]:
        rows.extend(stage["sos"])
    return np.asarray(rows, dtype=np.float64)


def nao_preprocess(x: np.ndarray, fs: float, sos: np.ndarray) -> np.ndarray:
    y = resample(x, fs, 256.0)
    y = sosfiltfilt(sos, y).astype(np.float32)
    y = zscore(y)
    return fit_length(y, 7680)


def founder_filter(x: np.ndarray, fs: float) -> np.ndarray:
    y = resample(x, fs, 500.0)
    from scipy.ndimage import median_filter
    from scipy.signal import butter, filtfilt, iirnotch

    b, a = iirnotch(50.0, 30.0, 500.0)
    y = filtfilt(b, a, y)
    b, a = butter(4, [0.67, 40.0], btype="bandpass", fs=500.0)
    y = filtfilt(b, a, y)
    k = int(0.4 * 500) + 1
    if k % 2 == 0:
        k += 1
    baseline = median_filter(y, size=k, mode="reflect")
    return (y - baseline).astype(np.float32)


def founder_windows(x500: np.ndarray) -> list[np.ndarray]:
    win = 5000
    hop = 2500
    if x500.size < win:
        pad = np.zeros(win, dtype=np.float32)
        off = (win - x500.size) // 2
        pad[off : off + x500.size] = x500
        return [zscore(pad)]
    out = []
    start = 0
    while start + win <= x500.size:
        out.append(zscore(x500[start : start + win]))
        start += hop
    if not out:
        out.append(zscore(x500[-win:]))
    return out


def map_founder(probs: np.ndarray) -> tuple[str, float, float]:
    p_af = 0.0
    p_n = 0.0
    p_o = 0.0
    for name, p in zip(FOUNDER_LABELS, probs):
        if name in AF_NAMES:
            p_af = max(p_af, float(p))
        elif name in NORMAL_NAMES:
            p_n = max(p_n, float(p))
        elif name in GENERIC_NAMES:
            continue
        else:
            p_o = max(p_o, float(p))
    if p_af >= 0.45 and p_af >= p_n:
        return "A", p_af, p_af
    if p_n >= 0.40 and p_n >= p_af and p_n >= p_o * 0.85:
        return "N", p_n, p_af
    return "O", max(p_o, 1.0 - max(p_af, p_n)), p_af


def watch_like(x: np.ndarray, fs: float, rng: np.random.Generator) -> tuple[np.ndarray, float]:
    """Same 500 Hz capture both models must read — Galaxy Watch proxy."""
    y = resample(x, fs, 500.0)
    n = y.size
    t = np.arange(n) / 500.0
    y = y.astype(np.float64)
    y += 0.08 * np.sin(2 * np.pi * 0.33 * t) * float(np.std(y) + 1e-6)
    y += 0.04 * np.sin(2 * np.pi * 50.0 * t) * float(np.std(y) + 1e-6)
    y += rng.normal(0.0, 0.06 * float(np.std(y) + 1e-6), size=n)
    if rng.random() < 0.35:
        a = int(rng.integers(0, max(1, n - 400)))
        y[a : a + int(rng.integers(80, 400))] = y[a]
    if rng.random() < 0.5:
        y = -y
    return y.astype(np.float32), 500.0


class NaoRunner:
    def __init__(self, model_path: Path, filters_path: Path):
        from ai_edge_litert.interpreter import Interpreter

        self.sos = load_nao_filters(filters_path)
        self.it = Interpreter(model_path=str(model_path))
        self.it.allocate_tensors()
        self.inp = self.it.get_input_details()[0]
        self.out = self.it.get_output_details()[0]

    def predict(self, x: np.ndarray, fs: float) -> tuple[str, np.ndarray]:
        arr = nao_preprocess(x, fs, self.sos).reshape(1, 7680, 1)
        if self.inp["dtype"] == np.int8:
            scale, zp = self.inp["quantization"]
            q = np.clip(np.round(arr / scale + zp), -128, 127).astype(np.int8)
            self.it.set_tensor(self.inp["index"], q)
            self.it.invoke()
            raw = self.it.get_tensor(self.out["index"]).astype(np.float32)
            oscale, ozp = self.out["quantization"]
            probs = (raw - ozp) * oscale
        else:
            self.it.set_tensor(self.inp["index"], arr.astype(np.float32))
            self.it.invoke()
            probs = self.it.get_tensor(self.out["index"]).astype(np.float32)
        probs = np.ravel(probs)
        if probs.min() < 0 or abs(float(probs.sum()) - 1.0) > 0.05:
            e = np.exp(probs - probs.max())
            probs = e / e.sum()
        return NAO_LABELS[int(np.argmax(probs))], probs


class FounderRunner:
    def __init__(self, onnx_path: Path):
        import onnxruntime as ort

        self.sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])

    def predict(self, x: np.ndarray, fs: float) -> tuple[str, np.ndarray, float]:
        filt = founder_filter(x, fs)
        windows = founder_windows(filt)
        acc = np.zeros(len(FOUNDER_LABELS), dtype=np.float64)
        for w in windows:
            inp = w.reshape(1, 1, 5000).astype(np.float32)
            acc += self.sess.run(None, {"ecg": inp})[0][0]
        acc /= len(windows)
        label, conf, p_af = map_founder(acc)
        return label, acc.astype(np.float32), p_af


def find_records(root: Path) -> list[tuple[str, Path, str]]:
    refs = list(root.rglob("REFERENCE.csv"))
    if not refs:
        refs = [p for p in root.rglob("REFERENCE*.csv") if "original" not in p.name.lower()]
    if not refs:
        raise FileNotFoundError(f"no REFERENCE*.csv under {root}")
    ref = refs[0]
    mats = {p.stem: p for p in root.rglob("*.mat")}
    rows = []
    for line in ref.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or "," not in line:
            continue
        rec, lab = [p.strip() for p in line.split(",")[:2]]
        lab = lab.upper()
        if lab not in {"N", "A", "O", "~"}:
            continue
        mat = mats.get(rec)
        if mat is None:
            continue
        rows.append((rec, mat, lab))
    return rows


def load_record(path: Path) -> np.ndarray:
    mat = loadmat(str(path))
    key = "val" if "val" in mat else next(k for k in mat if not k.startswith("_"))
    x = np.asarray(mat[key], dtype=np.float64).squeeze()
    if x.ndim > 1:
        x = x[0]
    return x.astype(np.float32)


def metrics(y_true: list[str], y_pred: list[str], p_af: list[float]) -> dict:
    labels = ["N", "A", "O"]
    prec, rec, f1, sup = precision_recall_fscore_support(
        y_true, y_pred, labels=labels, zero_division=0
    )
    out = {
        "n": len(y_true),
        "accuracy": float(accuracy_score(y_true, y_pred)),
        "macro_f1": float(f1_score(y_true, y_pred, labels=labels, average="macro", zero_division=0)),
        "confusion": confusion_matrix(y_true, y_pred, labels=labels).tolist(),
    }
    for i, lab in enumerate(labels):
        out[f"{lab}_precision"] = float(prec[i])
        out[f"{lab}_recall"] = float(rec[i])
        out[f"{lab}_f1"] = float(f1[i])
        out[f"{lab}_support"] = int(sup[i])
    y_bin = [1 if t == "A" else 0 for t in y_true]
    if len(set(y_bin)) == 2:
        out["af_auroc"] = float(roc_auc_score(y_bin, p_af))
        pred_bin = [1 if p == "A" else 0 for p in y_pred]
        out["af_f1"] = float(f1_score(y_bin, pred_bin, zero_division=0))
    return out


def pick_subset(rows: list[tuple[str, Path, str]], per_class: int, seed: int) -> list[tuple[str, Path, str]]:
    rng = np.random.default_rng(seed)
    buckets: dict[str, list] = defaultdict(list)
    for row in rows:
        if row[2] == "~":
            continue
        buckets[row[2]].append(row)
    chosen = []
    for lab in NAO_LABELS:
        items = buckets[lab]
        rng.shuffle(items)
        take = items if per_class <= 0 or per_class >= len(items) else items[:per_class]
        chosen.extend(take)
    rng.shuffle(chosen)
    return chosen


def fmt(m: dict) -> str:
    lines = [
        f"n={m['n']}  acc={m['accuracy']:.3f}  macro-F1={m['macro_f1']:.3f}",
        f"AF AUROC={m.get('af_auroc', float('nan')):.3f}  AF F1={m.get('af_f1', float('nan')):.3f}",
    ]
    for lab in NAO_LABELS:
        lines.append(
            f"  {lab}: P={m[f'{lab}_precision']:.3f} R={m[f'{lab}_recall']:.3f} "
            f"F1={m[f'{lab}_f1']:.3f} n={m[f'{lab}_support']}"
        )
    return "\n".join(lines)


def write_report(path: Path, conditions: dict, counts: Counter) -> None:
    chunks = [
        "# nao_full vs ECGFounder 1-lead",
        "",
        "Data: PhysioNet/CinC 2017 public AliveCor set (single-lead, 300 Hz, 9–60 s).",
        "This is the closest open corpus to a Galaxy Watch strip.",
        "",
        "Class counts used:",
        ", ".join(f"{k}={v}" for k, v in sorted(counts.items())),
        "",
        "## Bias you should know",
        "",
        "- `nao_full` is a 3-class N/A/O head at 256 Hz / 30 s. That *is* the 2017 task,",
        "  so this set may overstate nao if GeminiMan trained on it.",
        "- ECGFounder was trained on hospital lead-I ECGs. 2017 is external for it.",
        "- `watch-like` resamples every record to 500 Hz and adds baseline wander,",
        "  50 Hz hum, EMG, dropouts, and random polarity. Both models see that same",
        "  degraded strip. That condition is the fairer Galaxy Watch proxy.",
        "",
    ]
    for name, pair in conditions.items():
        chunks.append(f"## {name}")
        chunks.append("")
        for model, m in pair.items():
            chunks.append(f"### {model}")
            chunks.append("```")
            chunks.append(fmt(m))
            chunks.append("```")
            chunks.append("")
            cm = m["confusion"]
            chunks.append("Confusion (rows=true N,A,O; cols=pred N,A,O):")
            chunks.append(f"`{cm}`")
            chunks.append("")
    path.write_text("\n".join(chunks), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, default=ROOT / "models" / "eval" / "challenge-2017")
    parser.add_argument("--per-class", type=int, default=250)
    parser.add_argument("--seed", type=int, default=17)
    parser.add_argument("--nao", type=Path, default=ROOT / "models" / "nao" / "nao_full_ecg_model_fp32.tflite")
    parser.add_argument("--founder", type=Path, default=ROOT / "app" / "src" / "main" / "assets" / "ecg" / "ecgfounder_1lead.onnx")
    parser.add_argument("--filters", type=Path, default=ROOT / "models" / "nao" / "ecg_filters_256hz.json")
    parser.add_argument("--out", type=Path, default=ROOT / "models" / "eval" / "nao_vs_founder")
    args = parser.parse_args()

    rows = find_records(args.data)
    subset = pick_subset(rows, args.per_class, args.seed)
    print("records", len(subset), Counter(r[2] for r in subset))

    nao = NaoRunner(args.nao, args.filters)
    founder = FounderRunner(args.founder)
    rng = np.random.default_rng(args.seed)

    buckets = {
        "clean_2017": {"nao": {"yt": [], "yp": [], "paf": []}, "founder": {"yt": [], "yp": [], "paf": []}},
        "watch_like": {"nao": {"yt": [], "yp": [], "paf": []}, "founder": {"yt": [], "yp": [], "paf": []}},
    }

    for i, (rec, mat, lab) in enumerate(subset, 1):
        x = load_record(mat)
        fs = 300.0
        nlab, nprob = nao.predict(x, fs)
        flab, fprob, faf = founder.predict(x, fs)
        buckets["clean_2017"]["nao"]["yt"].append(lab)
        buckets["clean_2017"]["nao"]["yp"].append(nlab)
        buckets["clean_2017"]["nao"]["paf"].append(float(nprob[1]))
        buckets["clean_2017"]["founder"]["yt"].append(lab)
        buckets["clean_2017"]["founder"]["yp"].append(flab)
        buckets["clean_2017"]["founder"]["paf"].append(float(faf))

        wx, wfs = watch_like(x, fs, rng)
        nlab, nprob = nao.predict(wx, wfs)
        flab, fprob, faf = founder.predict(wx, wfs)
        buckets["watch_like"]["nao"]["yt"].append(lab)
        buckets["watch_like"]["nao"]["yp"].append(nlab)
        buckets["watch_like"]["nao"]["paf"].append(float(nprob[1]))
        buckets["watch_like"]["founder"]["yt"].append(lab)
        buckets["watch_like"]["founder"]["yp"].append(flab)
        buckets["watch_like"]["founder"]["paf"].append(float(faf))

        if i % 50 == 0 or i == len(subset):
            print(f"{i}/{len(subset)} last={rec} true={lab} nao={nlab} founder={flab}", flush=True)

    args.out.mkdir(parents=True, exist_ok=True)
    report = {}
    for cond, models in buckets.items():
        report[cond] = {}
        for model, d in models.items():
            m = metrics(d["yt"], d["yp"], d["paf"])
            report[cond][model] = m
            print("====", cond, model)
            print(fmt(m))
    (args.out / "metrics.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    with (args.out / "predictions.csv").open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["record", "true", "nao_clean", "founder_clean", "nao_watch", "founder_watch"])
        for i, (rec, _, lab) in enumerate(subset):
            w.writerow(
                [
                    rec,
                    lab,
                    buckets["clean_2017"]["nao"]["yp"][i],
                    buckets["clean_2017"]["founder"]["yp"][i],
                    buckets["watch_like"]["nao"]["yp"][i],
                    buckets["watch_like"]["founder"]["yp"][i],
                ]
            )
    write_report(args.out / "REPORT.md", report, Counter(r[2] for r in subset))
    print("wrote", args.out)


if __name__ == "__main__":
    main()

"""Download MIT-BIH / NSTDB and convert them for the opt-in Kotlin BPM benchmark.

Run this CLI explicitly. It is not invoked by Gradle. Converted files live under
`_analysis/ecg_benchmark/` (gitignored). NeuroKit2 0.2.13 is an additional
reference pipeline only; WFDB `.atr` beat labels remain the MIT-BIH ground truth.
"""

from __future__ import annotations

import argparse
import csv
import math
import re
import sys
from pathlib import Path

import numpy as np
import wfdb
from scipy.signal import resample_poly

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = ROOT / "_analysis" / "ecg_benchmark"
TARGET_HZ = 500
PACED_MITDB = frozenset({"102", "104", "107", "217"})
MITDB_RECORDS = (
    "100", "101", "102", "103", "104", "105", "106", "107", "108", "109",
    "111", "112", "113", "114", "115", "116", "117", "118", "119", "121",
    "122", "123", "124", "200", "201", "202", "203", "205", "207", "208",
    "209", "210", "212", "213", "214", "215", "217", "219", "220", "221",
    "222", "223", "228", "230", "231", "232", "233", "234",
)
NSTDB_RECORDS = (
    "118e24", "118e18", "118e12", "118e06", "118e00", "118e_6",
    "119e24", "119e18", "119e12", "119e06", "119e00", "119e_6",
)
BEAT_SYMBOLS = frozenset("NLRASaJVFEeQjn?Br")
SNR_SUFFIX = re.compile(r"e(_?\d+)$")
RECORD_STEM = re.compile(r"^(\d+)")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Prepare PhysioNet MIT-BIH and NSTDB records for the opt-in BPM benchmark.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Convert at most N records (MIT-BIH first, then NSTDB). Useful for a smoke subset.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT,
        help=f"Converted output directory (default: {DEFAULT_OUTPUT})",
    )
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="Reconvert records even if signal.f32 already exists.",
    )
    parser.add_argument(
        "--skip-neurokit",
        action="store_true",
        help="Do not write the NeuroKit2 0.2.13 reference sidecar.",
    )
    return parser.parse_args()


def nstdb_snr_db(record_id: str) -> str:
    match = SNR_SUFFIX.search(record_id)
    if match is None:
        return ""
    token = match.group(1)
    if token.startswith("_"):
        return str(-int(token[1:]))
    return str(int(token))


def pick_channel(sig_names: list[str]) -> int:
    upper = [name.upper() for name in sig_names]
    for index, name in enumerate(upper):
        if "MLII" in name or name in {"ML2", "II"}:
            return index
    return 0


def to_mv(signal: np.ndarray, unit: str) -> np.ndarray:
    normalized = unit.strip().lower().replace("µ", "u")
    if normalized in {"uv", "uvolt", "microvolt"}:
        return signal / 1000.0
    if normalized in {"v", "volt"}:
        return signal * 1000.0
    return signal


def resample_to_target(signal: np.ndarray, source_hz: float) -> np.ndarray:
    source = int(round(source_hz))
    if source == TARGET_HZ:
        return np.asarray(signal, dtype=np.float64)
    gcd = math.gcd(TARGET_HZ, source)
    resampled = resample_poly(signal, TARGET_HZ // gcd, source // gcd)
    expected = int(round(len(signal) * TARGET_HZ / source))
    if len(resampled) > expected:
        return resampled[:expected]
    if len(resampled) < expected:
        padded = np.zeros(expected, dtype=np.float64)
        padded[: len(resampled)] = resampled
        return padded
    return resampled


def load_record(database: str, record_id: str):
    record = wfdb.rdrecord(record_id, pn_dir=database)
    try:
        annotation = wfdb.rdann(record_id, "atr", pn_dir=database)
    except Exception:
        stem = RECORD_STEM.match(record_id)
        if stem is None:
            raise
        annotation = wfdb.rdann(stem.group(1), "atr", pn_dir="mitdb")
    return record, annotation


def beat_rows(annotation, fs: float) -> list[tuple[int, str]]:
    rows: list[tuple[int, str]] = []
    for sample, symbol in zip(annotation.sample, annotation.symbol):
        if symbol not in BEAT_SYMBOLS:
            continue
        time_ms = int(round(float(sample) * 1000.0 / fs))
        rows.append((time_ms, symbol))
    return rows


def sign_factor_from_beats(signal: np.ndarray, annotation, fs: float) -> int:
    values = []
    for sample, symbol in zip(annotation.sample, annotation.symbol):
        if symbol not in BEAT_SYMBOLS:
            continue
        index = int(sample)
        if 0 <= index < len(signal):
            values.append(float(signal[index]))
    if not values:
        return 1
    return 1 if float(np.median(values)) >= 0.0 else -1


def neurokit_rpeak_ms(signal: np.ndarray, fs: float) -> list[int] | None:
    try:
        import neurokit2 as nk
    except ImportError:
        print("neurokit2 is not installed; skipping reference sidecar", file=sys.stderr)
        return None
    clean = nk.ecg_clean(signal, sampling_rate=fs, method="neurokit")
    _markers, peak_info = nk.ecg_peaks(
        clean,
        sampling_rate=fs,
        method="neurokit",
        correct_artifacts=False,
    )
    peaks = peak_info["ECG_R_Peaks"]
    return [int(round(float(index) * 1000.0 / fs)) for index in peaks]


def write_meta(path: Path, fields: dict[str, str]) -> None:
    lines = [f"{key}={value}" for key, value in fields.items()]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_csv(path: Path, header: tuple[str, ...], rows: list[tuple[object, ...]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(header)
        writer.writerows(rows)


def convert_record(
    database: str,
    record_id: str,
    output: Path,
    overwrite: bool,
    skip_neurokit: bool,
) -> dict[str, str] | None:
    dest = output / database / record_id
    signal_path = dest / "signal.f32"
    meta_path = dest / "meta.txt"
    beats_path = dest / "beats.csv"
    if signal_path.is_file() and meta_path.is_file() and beats_path.is_file() and not overwrite:
        print(f"skip existing {database}/{record_id}")
        return parse_existing_meta(meta_path, database, record_id)

    print(f"convert {database}/{record_id}")
    record, annotation = load_record(database, record_id)
    fs = float(record.fs)
    channel = pick_channel(list(record.sig_name))
    raw = np.asarray(record.p_signal[:, channel], dtype=np.float64)
    mv = to_mv(raw, str(record.units[channel]))
    sign = sign_factor_from_beats(mv, annotation, fs)
    resampled = resample_to_target(mv, fs).astype("<f4", copy=False)
    dest.mkdir(parents=True, exist_ok=True)
    resampled.tofile(signal_path)
    beats = beat_rows(annotation, fs)
    write_csv(beats_path, ("time_ms", "symbol"), beats)
    fields = {
        "record_id": record_id,
        "dataset": database,
        "sr_hz": str(TARGET_HZ),
        "n_samples": str(len(resampled)),
        "sign_factor": str(sign),
        "channel": str(record.sig_name[channel]),
        "original_sr_hz": str(int(round(fs))),
        "snr_db": nstdb_snr_db(record_id) if database == "nstdb" else "",
        "neurokit2": "skipped",
    }
    if not skip_neurokit:
        peaks = neurokit_rpeak_ms(mv, fs)
        if peaks is not None:
            write_csv(dest / "neurokit_rpeaks.csv", ("time_ms",), [(value,) for value in peaks])
            fields["neurokit2"] = "0.2.13"
        else:
            fields["neurokit2"] = "unavailable"
    write_meta(meta_path, fields)
    return fields


def parse_existing_meta(path: Path, database: str, record_id: str) -> dict[str, str]:
    fields = {"record_id": record_id, "dataset": database, "snr_db": "", "sign_factor": "1", "n_samples": "0", "sr_hz": str(TARGET_HZ)}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        fields[key] = value
    return fields


def selected_records(limit: int | None) -> list[tuple[str, str]]:
    records: list[tuple[str, str]] = [
        ("mitdb", record_id) for record_id in MITDB_RECORDS if record_id not in PACED_MITDB
    ]
    records.extend(("nstdb", record_id) for record_id in NSTDB_RECORDS)
    if limit is None:
        return records
    if limit < 1:
        raise SystemExit("--limit must be >= 1")
    return records[:limit]


def main() -> None:
    args = parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    rows = []
    failures = []
    for database, record_id in selected_records(args.limit):
        try:
            fields = convert_record(
                database,
                record_id,
                output,
                overwrite=args.overwrite,
                skip_neurokit=args.skip_neurokit,
            )
        except Exception as exc:
            print(f"FAILED {database}/{record_id}: {exc}", file=sys.stderr)
            failures.append(f"{database}/{record_id}")
            continue
        if fields is None:
            continue
        rows.append(
            {
                "dataset": fields.get("dataset", database),
                "record_id": fields.get("record_id", record_id),
                "path": f"{database}/{record_id}",
                "snr_db": fields.get("snr_db", ""),
                "sign_factor": fields.get("sign_factor", "1"),
                "n_samples": fields.get("n_samples", "0"),
                "sr_hz": fields.get("sr_hz", str(TARGET_HZ)),
            }
        )
    manifest = output / "manifest.csv"
    with manifest.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=["dataset", "record_id", "path", "snr_db", "sign_factor", "n_samples", "sr_hz"],
        )
        writer.writeheader()
        writer.writerows(rows)
    print(f"wrote {len(rows)} records to {manifest}")
    if failures:
        raise SystemExit("failed records: " + ", ".join(failures))


if __name__ == "__main__":
    main()

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
    try:
        record = wfdb.rdrecord(record_id, pn_dir=database)
        annotation = _load_annotation(database, record_id)
        return record, annotation
    except Exception as exc:
        try:
            return load_record_http(database, record_id)
        except Exception:
            raise exc


def _load_annotation(database: str, record_id: str):
    try:
        return wfdb.rdann(record_id, "atr", pn_dir=database)
    except Exception:
        stem = RECORD_STEM.match(record_id)
        if stem is None:
            raise
        return wfdb.rdann(stem.group(1), "atr", pn_dir="mitdb")


PHYSIONET_FILE = "https://physionet.org/files/{database}/1.0.0/{name}"


def _http_bytes(database: str, name: str) -> bytes:
    from urllib.request import Request, urlopen

    url = PHYSIONET_FILE.format(database=database, name=name)
    request = Request(url, headers={"User-Agent": "GalaxyVitals-ecg-benchmark/1.0"})
    with urlopen(request, timeout=120) as response:
        return response.read()


def _parse_gain(token: str, adc_zero: int) -> tuple[float, int, str]:
    unit = "mV"
    left = token
    if "/" in token:
        left, unit = token.split("/", 1)
    if "(" in left and left.endswith(")"):
        gain_text, baseline_text = left[:-1].split("(", 1)
        return float(gain_text), int(baseline_text), unit
    return float(left), adc_zero, unit


def _signed12(values: np.ndarray) -> np.ndarray:
    values = values.astype(np.int16, copy=False)
    return np.where(values >= 2048, values - 4096, values).astype(np.int16, copy=False)


def _decode_212(payload: bytes, sample_count: int) -> np.ndarray:
    raw = np.frombuffer(payload, dtype=np.uint8)
    expected = sample_count * 3
    if raw.size != expected:
        raise ValueError(f"format 212 byte count {raw.size} != expected {expected}")
    frames = raw.reshape(-1, 3).astype(np.int16)
    first = frames[:, 0] | ((frames[:, 1] & 0x0F) << 8)
    second = frames[:, 2] | ((frames[:, 1] & 0xF0) << 4)
    return np.column_stack((_signed12(first), _signed12(second)))


def _decode_16(payload: bytes, sample_count: int, signal_count: int) -> np.ndarray:
    raw = np.frombuffer(payload, dtype="<i2")
    expected = sample_count * signal_count
    if raw.size != expected:
        raise ValueError(f"format 16 sample count {raw.size} != expected {expected}")
    return raw.reshape(sample_count, signal_count)


def _signed_checksum(values: np.ndarray) -> int:
    unsigned = int(np.sum(values.astype(np.int64))) & 0xFFFF
    return unsigned - 0x10000 if unsigned >= 0x8000 else unsigned


def _parse_header(database: str, record_id: str):
    from types import SimpleNamespace

    text = _http_bytes(database, f"{record_id}.hea").decode("ascii")
    lines = [line.strip() for line in text.splitlines() if line.strip() and not line.startswith("#")]
    head = lines[0].split()
    signal_count = int(head[1])
    fs = float(head[2].split("/", 1)[0])
    sample_count = int(head[3])
    specs = []
    for line in lines[1 : 1 + signal_count]:
        fields = line.split()
        fmt_text = fields[1].split("+", 1)[0].split(":", 1)[0].split("x", 1)[0]
        fmt = int(fmt_text)
        adc_zero = int(fields[4])
        gain, baseline, unit = _parse_gain(fields[2], adc_zero)
        specs.append(
            SimpleNamespace(
                filename=fields[0],
                fmt=fmt,
                gain=gain,
                baseline=baseline,
                initial=int(fields[5]),
                checksum=int(fields[6]),
                unit=unit,
                name=" ".join(fields[8:]),
            )
        )
    return fs, sample_count, specs


def _atr_pairs(payload: bytes) -> np.ndarray:
    raw = np.frombuffer(payload, dtype=np.uint8)
    if raw.size % 2:
        raise ValueError("annotation byte count is not even")
    return raw.reshape(-1, 2)


_ATR_SYMBOLS = {
    0: " ", 1: "N", 2: "L", 3: "R", 4: "a", 5: "V", 6: "F",
    7: "J", 8: "A", 9: "S", 10: "E", 11: "j", 12: "/", 13: "Q",
    14: "~", 16: "|", 18: "s", 19: "T", 20: "*", 21: "D",
    22: '"', 23: "=", 24: "p", 25: "B", 26: "^", 27: "t",
    28: "+", 29: "u", 30: "?", 31: "!", 32: "[", 33: "]",
    34: "e", 35: "n", 36: "@", 37: "x", 38: "f", 39: "(",
    40: ")", 41: "r",
}


def _atr_core(pairs: np.ndarray, index: int) -> tuple[int, int, int]:
    delta = 0
    while int(pairs[index, 1]) >> 2 == 59:
        skip = (
            (int(pairs[index + 1, 0]) << 16)
            + (int(pairs[index + 1, 1]) << 24)
            + int(pairs[index + 2, 0])
            + (int(pairs[index + 2, 1]) << 8)
        )
        if skip > 0x7FFFFFFF:
            skip -= 0x100000000
        delta += skip
        index += 3
    label = int(pairs[index, 1]) >> 2
    delta += int(pairs[index, 0]) + 256 * (int(pairs[index, 1]) & 3)
    return delta, label, index + 1


def _read_atr(database: str, record_id: str):
    from types import SimpleNamespace
    from urllib.error import HTTPError

    try:
        pairs = _atr_pairs(_http_bytes(database, f"{record_id}.atr"))
    except HTTPError:
        stem = RECORD_STEM.match(record_id)
        if stem is None:
            raise
        pairs = _atr_pairs(_http_bytes("mitdb", f"{stem.group(1)}.atr"))
    samples: list[int] = []
    symbols: list[str] = []
    total = 0
    index = 0
    while index < len(pairs) - 1:
        delta, label, index = _atr_core(pairs, index)
        total += delta
        if label == 0:
            break
        samples.append(total)
        symbols.append(_ATR_SYMBOLS.get(label, " "))
        while index < len(pairs):
            extra = int(pairs[index, 1]) >> 2
            if extra <= 59:
                break
            if extra == 63:
                length = int(pairs[index, 0])
                index += 1 + (length + 1) // 2
            else:
                index += 1
    return SimpleNamespace(sample=np.asarray(samples, dtype=np.int64), symbol=symbols)


def load_record_http(database: str, record_id: str):
    from types import SimpleNamespace

    fs, sample_count, specs = _parse_header(database, record_id)
    if not specs:
        raise ValueError(f"no signals in {database}/{record_id}")
    filenames = {spec.filename for spec in specs}
    if len(filenames) != 1:
        raise ValueError(f"separate signal files unsupported for {database}/{record_id}")
    payload = _http_bytes(database, specs[0].filename)
    fmt = specs[0].fmt
    if any(spec.fmt != fmt for spec in specs):
        raise ValueError(f"mixed WFDB formats unsupported for {database}/{record_id}")
    if fmt == 212:
        if len(specs) != 2:
            raise ValueError(f"format 212 requires two channels for {database}/{record_id}")
        digital = _decode_212(payload, sample_count)
    elif fmt == 16:
        digital = _decode_16(payload, sample_count, len(specs))
    else:
        raise ValueError(f"unsupported WFDB format {fmt} for {database}/{record_id}")
    for channel, spec in enumerate(specs):
        if int(digital[0, channel]) != spec.initial:
            raise ValueError(f"initial-value check failed for {database}/{record_id} channel {channel}")
        if _signed_checksum(digital[:, channel]) != spec.checksum:
            raise ValueError(f"checksum failed for {database}/{record_id} channel {channel}")
    physical = np.empty(digital.shape, dtype=np.float64)
    for channel, spec in enumerate(specs):
        physical[:, channel] = (digital[:, channel] - spec.baseline) / spec.gain
    record = SimpleNamespace(
        fs=fs,
        sig_name=[spec.name for spec in specs],
        units=[spec.unit for spec in specs],
        p_signal=physical,
    )
    annotation = _read_atr(database, record_id)
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
    window = max(1, int(round(0.2 * fs)))
    values = []
    for sample, symbol in zip(annotation.sample, annotation.symbol):
        if symbol not in BEAT_SYMBOLS:
            continue
        index = int(sample)
        if 0 <= index < len(signal):
            lo = max(0, index - window)
            hi = min(len(signal), index + window)
            baseline = float(np.median(signal[lo:hi]))
            values.append(float(signal[index]) - baseline)
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
    if args.limit is None:
        missing = missing_required_records(output)
        if missing:
            raise SystemExit("missing required records: " + ", ".join(missing))


def missing_required_records(output: Path) -> list[str]:
    missing: list[str] = []
    for database, record_id in selected_records(None):
        dest = output / database / record_id
        if not (dest / "signal.f32").is_file() or not (dest / "beats.csv").is_file() or not (dest / "meta.txt").is_file():
            missing.append(f"{database}/{record_id}")
    return missing


if __name__ == "__main__":
    main()

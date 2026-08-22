"""Create a local, deidentified ECG validation package; never uploads data."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import hmac
import json
from pathlib import Path

MAX_COMPRESSED = 8 * 1024 * 1024
MAX_UNCOMPRESSED = 16 * 1024 * 1024


def pseudonym(secret: bytes, participant_key: str) -> str:
    return hmac.new(secret, participant_key.encode("utf-8"), hashlib.sha256).hexdigest()


def split_for(group_id: str) -> str:
    bucket = int(group_id[:8], 16) % 100
    return "train" if bucket < 70 else "validation" if bucket < 85 else "test"


def scrub(source: Path, destination: Path) -> dict[str, object]:
    if not source.is_file() or source.stat().st_size not in range(1, MAX_COMPRESSED + 1):
        raise ValueError(f"invalid compressed ECG size: {source.name}")
    with gzip.open(source, "rb") as stream:
        raw = stream.read(MAX_UNCOMPRESSED + 1)
    if len(raw) > MAX_UNCOMPRESSED:
        raise ValueError(f"expanded ECG is too large: {source.name}")
    text = raw.decode("utf-8")
    lines = text.splitlines()
    if not lines or not lines[0].startswith("#meta="):
        raise ValueError(f"missing ECG metadata: {source.name}")
    meta = json.loads(lines[0][6:])
    meta["ts_start"] = 0
    meta["watch_info"] = ""
    meta["deidentified"] = True
    payload = ("#meta=" + json.dumps(meta, separators=(",", ":")) + "\n" + "\n".join(lines[1:]) + "\n").encode()
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("wb") as raw_output:
        with gzip.GzipFile(fileobj=raw_output, mode="wb", compresslevel=9, mtime=0) as output:
            output.write(payload)
    sample_count = sum(
        1 for line in lines[1:]
        if line and not line.startswith("#") and not line.startswith(("rel_ms", "timestamp_ms"))
    )
    return {
        "schema_version": int(meta.get("schema_version", 1)),
        "capture_source": str(meta.get("capture_source", "LEGACY")),
        "timing_trust": str(meta.get("timing_trust", "ASSUMED")),
        "sample_count": sample_count,
        "sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--participant-key", required=True)
    parser.add_argument("--salt-file", required=True, type=Path)
    args = parser.parse_args()
    secret = args.salt_file.read_bytes()
    if len(secret) < 32:
        raise SystemExit("salt file must contain at least 32 random bytes")
    group_id = pseudonym(secret, args.participant_key)
    rows = []
    for index, source in enumerate(args.inputs):
        record_id = hmac.new(secret, f"{args.participant_key}:{index}".encode(), hashlib.sha256).hexdigest()
        relative = Path("records") / f"{record_id}.csv.gz"
        metadata = scrub(source.resolve(), (args.output / relative).resolve())
        rows.append({
            "record_id": record_id,
            "participant_group": group_id,
            "split": split_for(group_id),
            "file": relative.as_posix(),
            **metadata,
        })
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = {
        "schema_version": 1,
        "deidentified": True,
        "grouping": "participant-level HMAC",
        "records": rows,
    }
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()

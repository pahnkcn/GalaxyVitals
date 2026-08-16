"""Export 1-lead ECGFounder to ONNX (+ optional dynamic INT8) for Android."""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

import numpy as np
import torch
from scipy.signal import butter, iirnotch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from net1d import Net1D  # noqa: E402


def build_model() -> Net1D:
    return Net1D(
        in_channels=1,
        base_filters=64,
        ratio=1,
        filter_list=[64, 160, 160, 400, 400, 1024, 1024],
        m_blocks_list=[2, 2, 2, 3, 3, 4, 4],
        kernel_size=16,
        stride=2,
        groups_width=16,
        n_classes=150,
        use_bn=False,
        use_do=False,
    )


def load_weights(path: Path, model: Net1D) -> None:
    print("loading", path)
    ckpt = torch.load(path, map_location="cpu", weights_only=False)
    state = ckpt["state_dict"] if isinstance(ckpt, dict) and "state_dict" in ckpt else ckpt
    missing, unexpected = model.load_state_dict(state, strict=False)
    print("missing", len(missing), "unexpected", len(unexpected))
    if missing:
        print(" missing sample", missing[:8])
    del ckpt, state


class SigmoidNet(torch.nn.Module):
    def __init__(self, inner: Net1D):
        super().__init__()
        self.inner = inner

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return torch.sigmoid(self.inner(x))


def write_filters(out_json: Path) -> None:
    fs = 500.0
    b_notch, a_notch = iirnotch(50.0, 30.0, fs)
    b_bp, a_bp = butter(N=4, Wn=[0.67, 40.0], btype="bandpass", fs=fs)
    payload = {
        "fs": 500,
        "window_samples": 5000,
        "notch": {"b": b_notch.tolist(), "a": a_notch.tolist()},
        "bandpass": {"b": b_bp.tolist(), "a": a_bp.tolist()},
        "median_sec": 0.4,
    }
    out_json.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print("wrote", out_json)


def export(pth: Path, dest_dir: Path) -> None:
    dest_dir.mkdir(parents=True, exist_ok=True)
    model = build_model()
    load_weights(pth, model)
    model.eval()
    wrapped = SigmoidNet(model)
    wrapped.eval()
    dummy = torch.zeros(1, 1, 5000)
    with torch.no_grad():
        probe = wrapped(dummy)
    print("probe", tuple(probe.shape), float(probe.min()), float(probe.max()))

    fp32 = dest_dir / "ecgfounder_1lead.onnx"
    torch.onnx.export(
        wrapped,
        dummy,
        fp32,
        input_names=["ecg"],
        output_names=["probs"],
        opset_version=17,
        dynamo=False,
    )
    print("exported", fp32, "bytes", fp32.stat().st_size)

    int8 = dest_dir / "ecgfounder_1lead_int8.onnx"
    try:
        from onnxruntime.quantization import QuantType, quantize_dynamic

        quantize_dynamic(str(fp32), str(int8), weight_type=QuantType.QInt8)
        print("quantized", int8, "bytes", int8.stat().st_size)
        chosen = int8
    except Exception as exc:
        print("quantize failed, using fp32:", exc)
        chosen = fp32

    assets = ROOT / "app" / "src" / "main" / "assets" / "ecg"
    assets.mkdir(parents=True, exist_ok=True)
    target = assets / "ecgfounder_1lead.onnx"
    shutil.copy2(chosen, target)
    print("copied", target, "bytes", target.stat().st_size)
    write_filters(assets / "filters.json")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--pth",
        type=Path,
        default=next(
            (
                candidate
                for candidate in (ROOT / "models" / "1_lead_ECGFounder.pth", ROOT / "1_lead_ECGFounder.pth")
                if candidate.exists()
            ),
            ROOT / "models" / "1_lead_ECGFounder.pth",
        ),
    )
    parser.add_argument("--out", type=Path, default=ROOT / "models")
    args = parser.parse_args()
    if not args.pth.exists():
        raise SystemExit(f"missing {args.pth}")
    export(args.pth, args.out)


if __name__ == "__main__":
    main()

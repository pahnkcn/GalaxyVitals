"""Export 1-lead ECGFounder to ONNX (+ optional dynamic INT8) for Android."""

from __future__ import annotations

import argparse
import hashlib
import hmac
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
from torch_safety import require_safe_torch_version  # noqa: E402

OFFICIAL_1_LEAD_SHA256 = "f863a38897fb49a27fec7e44008ea3c7bdbd29c77fa4a02ecbb8c56df4f37603"


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


def require_checkpoint_hash(path: Path, expected_sha256: str) -> None:
    expected = expected_sha256.strip().lower()
    if len(expected) != 64 or any(char not in "0123456789abcdef" for char in expected):
        raise ValueError("expected checkpoint SHA-256 must contain exactly 64 hex characters")
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    actual = digest.hexdigest()
    if not hmac.compare_digest(actual, expected):
        raise RuntimeError(
            f"checkpoint SHA-256 mismatch: expected {expected}, got {actual}"
        )


def load_weights(path: Path, model: Net1D, expected_sha256: str) -> None:
    print("loading", path)
    require_checkpoint_hash(path, expected_sha256)
    require_safe_torch_version(getattr(torch, "__version__", None))
    ckpt = torch.load(path, map_location="cpu", weights_only=True)
    state = ckpt["state_dict"] if isinstance(ckpt, dict) and "state_dict" in ckpt else ckpt
    missing, unexpected = model.load_state_dict(state, strict=False)
    print("missing", len(missing), "unexpected", len(unexpected))
    if missing or unexpected:
        raise RuntimeError(
            "checkpoint does not match ECGFounder 1-lead architecture: "
            f"missing={missing[:8]!r}, unexpected={unexpected[:8]!r}"
        )
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


def verify_onnx(path: Path, expected: np.ndarray) -> None:
    try:
        import onnx
        import onnxruntime as ort
    except ImportError as exc:
        raise RuntimeError(
            "ONNX verification requires both onnx and onnxruntime; refusing to copy an unverified model"
        ) from exc

    onnx.checker.check_model(onnx.load(str(path)))
    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    inputs = session.get_inputs()
    outputs = session.get_outputs()
    if len(inputs) != 1 or inputs[0].name != "ecg" or inputs[0].shape != [1, 1, 5000]:
        raise RuntimeError(f"unexpected ONNX input schema: {[(v.name, v.shape) for v in inputs]}")
    if len(outputs) != 1 or outputs[0].name != "probs" or outputs[0].shape != [1, 150]:
        raise RuntimeError(f"unexpected ONNX output schema: {[(v.name, v.shape) for v in outputs]}")
    actual = np.asarray(
        session.run(["probs"], {"ecg": np.zeros((1, 1, 5000), dtype=np.float32)})[0]
    )
    if actual.shape != (1, 150) or not np.isfinite(actual).all():
        raise RuntimeError("ONNX verification returned an invalid or non-finite output")
    np.testing.assert_allclose(actual, expected, rtol=1e-4, atol=1e-5)
    print("verified ONNX schema and PyTorch parity")


def export(pth: Path, dest_dir: Path, expected_sha256: str) -> None:
    dest_dir.mkdir(parents=True, exist_ok=True)
    model = build_model()
    load_weights(pth, model, expected_sha256)
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
    verify_onnx(fp32, probe.detach().cpu().numpy())

    int8 = dest_dir / "ecgfounder_1lead_int8.onnx"
    try:
        from onnxruntime.quantization import QuantType, quantize_dynamic

        quantize_dynamic(str(fp32), str(int8), weight_type=QuantType.QInt8)
        print("quantized", int8, "bytes", int8.stat().st_size)
    except Exception as exc:
        print("quantize failed, keeping fp32 only:", exc)

    # Android ORT CPU does not implement ConvInteger from dynamic INT8.
    # Ship the FP32 graph so the phone can actually run the screen.
    chosen = fp32

    assets = ROOT / "app" / "src" / "main" / "assets" / "ecg"
    assets.mkdir(parents=True, exist_ok=True)
    target = assets / "ecgfounder_1lead.onnx"
    temporary_target = target.with_name(f"{target.name}.tmp")
    try:
        shutil.copy2(chosen, temporary_target)
        temporary_target.replace(target)
    finally:
        temporary_target.unlink(missing_ok=True)
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
    parser.add_argument(
        "--expected-sha256",
        default=OFFICIAL_1_LEAD_SHA256,
        help="trusted SHA-256 for the checkpoint (defaults to the official 1-lead release)",
    )
    args = parser.parse_args()
    if not args.pth.exists():
        raise SystemExit(f"missing {args.pth}")
    export(args.pth, args.out, args.expected_sha256)


if __name__ == "__main__":
    main()

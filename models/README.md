# ECGFounder weights

Place the official MIT checkpoint here or at the repo root:

- `1_lead_ECGFounder.pth`

Then convert it for the phone app:

```bash
py -3.12 tools/ecgfounder/export_ecgfounder.py --pth 1_lead_ECGFounder.pth
```

That writes FP32 ONNX to `app/src/main/assets/ecg/ecgfounder_1lead.onnx`
(Android ORT CPU cannot run the dynamic-INT8 `ConvInteger` graph). The exporter
pins the official 1-lead checkpoint SHA-256 and verifies ONNX schema plus
PyTorch/ONNX Runtime numerical parity before copying the asset. For a separately
trusted custom checkpoint, pass its digest with `--expected-sha256`.
The raw `.pth` / `.onnx` files are gitignored because they are hundreds of megabytes.

Source: https://huggingface.co/PKUDigitalHealth/ECGFounder
License: MIT

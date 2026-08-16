# ECGFounder weights

Place the official MIT checkpoint here or at the repo root:

- `1_lead_ECGFounder.pth`

Then convert it for the phone app:

```bash
py -3.12 tools/ecgfounder/export_ecgfounder.py --pth 1_lead_ECGFounder.pth
```

That writes a quantized ONNX file to `app/src/main/assets/ecg/ecgfounder_1lead.onnx`.
The raw `.pth` / `.onnx` files are gitignored because they are hundreds of megabytes.

Source: https://huggingface.co/PKUDigitalHealth/ECGFounder
License: MIT

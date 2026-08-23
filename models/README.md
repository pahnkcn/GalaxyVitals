# ECG model artifacts

The active phone runtime uses the direct NAO3 student model documented in
[`nao3/README.md`](nao3/README.md). The SHA-bound FP32 reference is copied to
`app/src/main/assets/ecg/ecg_nao3_student_fp32.tflite`; FP16 and INT8 are kept
beside it under `nao3/converted/` for reproducible parity work but are not
packaged as fallbacks.

`models/nao/` contains the older GeminiMan NAO-v2 files used only for APK
comparison and preprocessing archaeology. They are not Android assets. The
retired ECGFounder ONNX file is preserved locally at
`models/archive/ecgfounder_1lead.onnx` and remains gitignored because of its
size.

The old ECGFounder conversion/evaluation tools remain under `tools/ecgfounder/`
for historical reproducibility; they are no longer part of the application
runtime or release bundle.

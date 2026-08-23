# ECG Measurement and NAO3 Replacement Specification

## User goal

Audit why Galaxy Watch 9 ECG recordings look abnormal, compare the acquisition and analysis path with the supplied GeminiMan phone/watch APKs, fix GalaxyVitals, and replace ECGFounder/NAO with the supplied `student_fp16.tflite`, `student_fp32.tflite`, and `ecg_nao3_int8.tflite` models under appropriate names and locations.

## Evidence and required behavior

- Samsung `ECG_ON_DEMAND` supplies raw ECG in mV at 500 Hz. The first `DataPoint` carries contact, sequence, and threshold metadata; `LEAD_OFF == 0` means contact and every non-zero value means no contact.
- GalaxyVitals already uses `ECG_ON_DEMAND`, reads all `ECG_MV` values, preserves sensor timestamps, and does not run a continuous tracker concurrently. These behaviors must remain.
- Raw ECG must remain byte-for-byte representable in the canonical CSV. Filtering for display or inference must never overwrite stored samples.
- The phone detail chart must display an oriented, display-only 0.5–40 Hz signal and scale the visible range. It must preserve narrow QRS peaks when reducing points for the canvas.
- Quality analysis must keep raw warnings such as `BASELINE_DRIFT`, but correctable raw warnings must not by themselves reject a window after filtering. Contact loss, clipping, missing data, flatline, held signal, impulse noise, and low amplitude remain analysis-fatal.
- Polarity must be applied exactly once: use a multiplier of `1` when `polarityNormalized == true`, otherwise use `signFactor`.
- A 15,000-sample 500-Hz recording spans timestamps 0 through 29,998 ms but represents 30 seconds. The UI must display `30s`.

## Model contract

- Class order is `N`, `A`, `O`.
- Input shape is `[1, 7680, 1]`, representing 30 seconds at 256 Hz.
- Preprocessing is: apply effective polarity; linear resample to 256 Hz; run the supplied three-section band-pass, 50-Hz notch, and 60-Hz notch as a forward/reverse zero-state SOS cascade; z-score the whole record; center-crop or center-zero-pad to 7,680 values.
- The three outputs are logits, not probabilities. Apply stable softmax before constructing `NaoDecision`.
- Runtime code must verify model input/output shapes and dtypes rather than relying on filename alone.

## Validated model placement

- `student_fp32.tflite` becomes `models/nao3/converted/ecg_nao3_student_fp32.tflite` and `app/src/main/assets/ecg/ecg_nao3_student_fp32.tflite`, the shipped/default float reference.
- `student_fp16.tflite` becomes `models/nao3/converted/ecg_nao3_student_fp16.tflite`, a host-parity candidate pending target-device verification.
- `ecg_nao3_int8.tflite` becomes `models/nao3/converted/ecg_nao3_student_int8.tflite`, retained as a speed candidate but not shipped or selected by the app.
- The supplied FP16 file is valid: `ai-edge-quantizer 0.9.0` re-exported FP32 to the exact same 4,100,128 bytes and SHA-256, and LiteRT 2.2.0 invoked it successfully.
- FP16 passed a deterministic 20-record numerical parity set against FP32: logit correlation `0.99999956`, maximum probability error `0.000756`, and class agreement `20/20`.
- INT8 failed that numerical gate: logit correlation `0.43464`, maximum probability error `0.68674`, and class agreement `10/20`. It must not be a runtime fallback or default.
- Target-device numerical verification remains outstanding because no ADB device is attached. The app must therefore prefer FP32, keep FP16 non-runtime despite its host parity, and make no GPU/latency claim.

## Packaging and compatibility

- Replace ONNX Runtime with LiteRT `2.1.3`, the newest release compatible with the repository's Kotlin 2.1 metadata, in the phone module.
- Package `.tflite` uncompressed so LiteRT can memory-map the asset.
- Replace the old ECGFounder bundle, 150-label table, calibrator, and proxy thresholds with a hash-bound NAO3 manifest that describes the model, filters, preprocessing, logits, and labels.
- Change the analysis compatibility ID so saved ECGs are reanalysed with NAO3.
- Remove the 123-MB ECGFounder ONNX file from Android assets without deleting the local source artifact; archive it under `models/archive/`.
- Keep the existing database schema and `N/A/O` fields. Old ECGFounder finding rows are no longer produced.

## Verification gates

- Protocol tests cover polarity normalization, baseline-drift recovery, 256-Hz preprocessing length/finite z-score behavior, stable softmax, and display filtering without raw mutation.
- App tests cover 30-second formatting and visible-range peak-preserving waveform reduction.
- Existing protocol, phone, and Wear tests remain green.
- Both phone and Wear debug APKs assemble.
- The release ECG bundle hash task passes.

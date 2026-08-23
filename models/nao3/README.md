# NAO3 student ECG models

These are the user-supplied direct three-class ECG models, renamed to bind the architecture and variant explicitly:

| Variant | Role | Bytes | SHA-256 |
|---|---|---:|---|
| `converted/ecg_nao3_student_fp32.tflite` | shipped float reference / quality row | 8,026,200 | `7400a2352c79275d5a4860a76a684cc0b6140e8385572de5a68027f7343a20ac` |
| `converted/ecg_nao3_student_fp16.tflite` | host-parity candidate | 4,100,128 | `b5b6cb3d4fd4df93b63c80abead883b289c90fa5cf5cdaac1c83ce1aaa7a1f1a` |
| `converted/ecg_nao3_student_int8.tflite` | rejected speed candidate | 2,291,216 | `7237a151dd8471d1659f59ad21215d3be149de7425a9803f9e399f972aaa1584` |

## Contract

- Input: FLOAT32 `[1,7680,1]`, 30 seconds at 256 Hz.
- Preprocess: effective polarity once, linear resample, five-section SOS forward/reverse filtering, whole-record z-score, center crop/pad.
- Output: three logits in `[N, A, O]` order; apply stable softmax.
- Android asset: only the FP32 reference is packaged. FP16 awaits target-device verification and INT8 is not a fallback.

## FP16 reproduction and parity

Toolchain: `ai-edge-quantizer==0.9.0`, `ai-edge-litert==2.2.0`, NumPy, Python 3.14.6 on Windows.

Android is pinned to `com.google.ai.edge.litert:litert:2.1.3`, the newest tested
release whose Kotlin metadata compiles with this repository's Kotlin 2.1.21
toolchain. Versions 2.1.4, 2.1.6, and 2.2.0 require Kotlin 2.3 metadata. A
LiteRT 2.1-series host runtime also loaded and invoked the exact shipped FP32
asset with FLOAT32 `[1,7680,1]` input, FLOAT32 `[1,3]` output, and finite logits.

Running `quantize_fp16.py` against the FP32 reference produced a byte-identical FP16 file: 4,100,128 bytes and the same `b5b6...a1f1a` hash as the supplied `student_fp16.tflite`. LiteRT allocated and invoked both files successfully. This confirms the external weight buffers are valid even though older/basic FlatBuffer readers may show zero-length inline buffers.

`verify_parity.py` generated 20 deterministic ECG-like, per-record-z-scored inputs. Results against FP32:

| Variant | Logit correlation | Max probability error | Mean probability error | Argmax agreement |
|---|---:|---:|---:|---:|
| FP16 | 0.99999956 | 0.00075561 | 0.00018401 | 20/20 |
| INT8 | 0.43464243 | 0.68673879 | 0.22630940 | 10/20 |

FP16 passes the numerical host gate but is not the runtime default because no Android target was attached for the required quantized-device gate. INT8 fails and is retained only for diagnosis. These synthetic inputs measure implementation parity, not clinical accuracy. Android packages FP32 until target-device numerical/latency validation is complete; no delegate or performance claim is made.

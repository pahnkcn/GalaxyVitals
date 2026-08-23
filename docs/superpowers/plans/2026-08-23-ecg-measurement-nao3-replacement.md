# ECG Measurement and NAO3 Replacement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Galaxy Watch 9 ECG strips display and pass quality checks correctly while replacing the ECGFounder 150-output pipeline with the supplied direct N/A/O NAO3 LiteRT model.

**Architecture:** Keep Samsung acquisition and canonical CSV raw, then split downstream work into three explicit paths: display-only filtering, quality/beat filtering, and NAO3 model preprocessing. The phone owns one reusable LiteRT interpreter whose FP32 asset and sidecar are SHA-bound at build and runtime; FP16 remains a host-parity candidate until target-device verification, while failed-gate INT8 remains diagnostic only.

**Tech Stack:** Kotlin/JVM 17, Android 32+, Wear OS, Samsung Health Sensor SDK, Jetpack Compose, LiteRT 2.1.3, Gradle Kotlin DSL, JUnit 4, Truth, TFLite FlatBuffers.

**Spec:** `docs/superpowers/specs/2026-08-23-ecg-measurement-nao3-replacement.md`

## Global Constraints

- Preserve raw Samsung `ECG_MV` values, metadata, timestamps, and stored CSV; display and model transforms are derived copies only.
- Keep `ECG_ON_DEMAND`, 500 Hz acquisition, `leadOff != 0` rejection, and no concurrent continuous Samsung tracker.
- Apply polarity once with `if (polarityNormalized) 1f else signFactor.toFloat()`.
- NAO3 consumes `[1,7680,1]` FLOAT32 and produces three FLOAT32 logits in `N,A,O` order.
- Ship `ecg_nao3_student_fp32.tflite`; retain FP16 pending its target-device gate and never select the failed-parity INT8 variant.
- Do not add an unvalidated clinical confidence threshold. Report argmax and softmax model score only after the existing signal-quality gate.
- Keep the existing Room schema and stored N/A/O fields.
- No target-device performance or delegate claim until an ADB-connected phone completes numerical verification.

---

### Task 1: Establish the model artifact and bundle contract

**Files:**
- Create: `models/nao3/README.md`
- Create: `models/nao3/converted/ecg_nao3_student_fp16.tflite`
- Create: `models/nao3/converted/ecg_nao3_student_fp32.tflite`
- Create: `models/nao3/converted/ecg_nao3_student_int8.tflite`
- Create: `app/src/main/assets/ecg/ecg_nao3_student_fp32.tflite`
- Create: `app/src/main/assets/ecg/ecg_nao3_filters_256hz.json`
- Create: `app/src/main/assets/ecg/ecg_nao3_bundle.json`
- Move: `app/src/main/assets/ecg/ecgfounder_1lead.onnx` to `models/archive/ecgfounder_1lead.onnx`
- Delete: `app/src/main/assets/ecg/analysis_bundle.json`
- Delete: `app/src/main/assets/ecg/labels.json`
- Delete: `app/src/main/assets/ecg/filters.json`
- Delete: `app/src/main/assets/ecg/nao_calibrator.json`
- Delete: `app/src/main/assets/ecg/decision_thresholds.json`

**Interfaces:**
- Consumes: the three root-level user model files and the verified hashes in the specification.
- Produces: `ecg/ecg_nao3_bundle.json` with `compatibility_id="ecg-nao3-student-256hz-v1"`, artifact SHA-256 values, `labels=["N","A","O"]`, `output_semantics="logits"`, input shape `[1,7680,1]`, and FP32 as `default_variant`.

- [ ] **Step 1: Re-run size, load, and numerical parity checks**

Use `ai_edge_litert.interpreter.Interpreter` to invoke FP32, FP16, and INT8 on the same 20 deterministic ECG-like, per-record-z-scored inputs. Require FP16 class agreement `1.0` and probability max error `< 0.001`; record the observed metrics and tool versions in `models/nao3/README.md`. Record INT8 as rejected because its observed class agreement is `0.5`.

- [ ] **Step 2: Rename and place models without losing the source variants**

Move the three supplied files into `models/nao3/converted/` under the specified names and copy only the FP32 reference into Android assets. Archive the old ONNX outside Android assets.

- [ ] **Step 3: Write the filter and model sidecars**

The filter sidecar contains all five SOS rows in this order: three band-pass rows, notch 50 Hz, notch 60 Hz. The bundle binds the FP32 SHA-256 `7400a2352c79275d5a4860a76a684cc0b6140e8385572de5a68027f7343a20ac` and the generated filter SHA.

- [ ] **Step 4: Verify artifacts**

Run `Get-FileHash -Algorithm SHA256` on all renamed models and confirm their hashes remain the values in the specification. Confirm no `.onnx` remains under `app/src/main/assets`.

### Task 2: Implement generic quality orientation and NAO3 preprocessing

**Files:**
- Create: `protocol/src/main/java/app/galaxyvitals/data/protocol/EcgPolarity.kt`
- Create: `protocol/src/main/java/app/galaxyvitals/data/protocol/Nao3Preprocess.kt`
- Modify: `protocol/src/main/java/app/galaxyvitals/data/protocol/EcgFounderPreprocess.kt`
- Modify: `protocol/src/main/java/app/galaxyvitals/data/protocol/EcgBeatAnalyzer.kt`
- Create: `protocol/src/test/java/app/galaxyvitals/data/protocol/Nao3PreprocessTest.kt`
- Modify: `protocol/src/test/java/app/galaxyvitals/data/protocol/EcgFounderPreprocessTest.kt`

**Interfaces:**
- Consumes: `ParsedEcgFile`, `PreparedRecording`, and the SOS contract from Task 1.
- Produces: `fun ParsedEcgFile.effectivePolarity(): Float` and `Nao3Preprocess.prepare(parsed: ParsedEcgFile): FloatArray` returning exactly 7,680 finite z-scored samples.

- [ ] **Step 1: Write failing polarity and baseline-drift tests**

Cover all four combinations of `polarityNormalized` and `signFactor`; assert normalized right-wrist data is not inverted twice. Build a 30-second synthetic ECG plus a 1.2-mV linear baseline ramp; assert the raw report retains `BASELINE_DRIFT`, at least three filtered windows survive, and quality is usable.

- [ ] **Step 2: Correct quality-window selection**

Use `parsed.effectivePolarity()` in quality and beat paths. Call `SignalQualityAnalyzer.assessWindow()` on the already band-pass-filtered slice, not the raw resampled slice. Keep global raw flags in the final report so the UI remains diagnostic.

- [ ] **Step 3: Write failing NAO3 contract tests**

Assert 15,000 samples at 500 Hz become 7,680 values; constant input produces finite zeros; a shorter recording is center-zero-padded after filtering/z-score; and applying a normalized right-wrist input matches the left-wrist reference rather than its negative.

- [ ] **Step 4: Implement exact GeminiMan-compatible preprocessing**

Implement linear output length `round(((n - 1) / sourceHz) * 256) + 1`, five-row zero-state SOS forward filtering, reversal, the same cascade, and reversal back. Then z-score with variance floor `1e-9` and center-fit to 7,680.

- [ ] **Step 5: Run protocol tests**

Run `./gradlew :protocol:test --tests '*Nao3PreprocessTest' --tests '*EcgFounderPreprocessTest' --tests '*EcgAccuracyPipelineTest'`. Expected: PASS.

### Task 3: Replace ECGFounder/ONNX inference with one reusable LiteRT engine

**Files:**
- Create: `app/src/main/java/app/galaxyvitals/analysis/EcgAnalysisBundle.kt`
- Create: `app/src/main/java/app/galaxyvitals/analysis/EcgRhythmEngine.kt`
- Create: `app/src/main/java/app/galaxyvitals/analysis/Nao3Postprocessor.kt`
- Delete: `app/src/main/java/app/galaxyvitals/analysis/EcgFounderEngine.kt`
- Delete: `app/src/main/java/app/galaxyvitals/analysis/AnalysisBundle.kt`
- Delete: `app/src/main/java/app/galaxyvitals/analysis/NaoCalibrator.kt`
- Modify: `app/src/main/java/app/galaxyvitals/AppContainer.kt`
- Modify: `app/src/main/java/app/galaxyvitals/data/EcgRepository.kt`
- Create: `app/src/test/java/app/galaxyvitals/analysis/Nao3PostprocessorTest.kt`

**Interfaces:**
- Consumes: `Nao3Preprocess.prepare(parsed)`, `EcgFounderPreprocess.prepare(parsed)` for the quality/beat gate, and the Task 1 bundle.
- Produces: `EcgRhythmEngine.analyze(parsed: ParsedEcgFile): AnalysisResult`; `Nao3Postprocessor.fromLogits(logits: FloatArray): NaoDecision`; `EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID`.

- [ ] **Step 1: Write failing stable-softmax tests**

Test `[1000,999,-1000]` for finite normalized probabilities, label order `N,A,O`, and exact argmax mapping for each class. Reject non-finite values and arrays whose size is not three.

- [ ] **Step 2: Implement the bundle loader**

Parse and verify every bundle field and SHA. Require the exact compatibility ID, input shape, output count, label order, FLOAT32 I/O, and logits semantics before the model is opened.

- [ ] **Step 3: Implement a lifecycle-safe LiteRT engine**

Memory-map the uncompressed asset with `AssetFileDescriptor`, build one lazy `org.tensorflow.lite.Interpreter`, allocate once, and serialize `run()` calls. Validate runtime tensor shape `[1,7680,1]`, output shape `[1,3]`, and FLOAT32 types. Reuse one direct input buffer and one `Array(1) { FloatArray(3) }` output buffer.

- [ ] **Step 4: Preserve quality behavior and remove unvalidated abstention**

Return `LOW_QUALITY` before inference when the corrected quality report is unusable. Otherwise run one full-record NAO3 input, stable-softmax the logits, and return `OK` with the argmax decision and score. Do not reuse the ECGFounder 150-output calibrator, old finding labels, class thresholds, or window-consensus threshold.

- [ ] **Step 5: Integrate repository and application ownership**

Rename `ecgFounder` to `ecgRhythmEngine`, persist no old finding rows, and update stale-analysis checks to the new compatibility ID. Keep analysis on the repository IO dispatcher so inference does not block Compose.

- [ ] **Step 6: Run phone unit tests**

Run `./gradlew :app:testDebugUnitTest --tests '*Nao3PostprocessorTest' --tests '*UserFacingAnalysisErrorTest'`. Expected: PASS.

### Task 4: Correct the detail waveform without mutating raw recordings

**Files:**
- Create: `protocol/src/main/java/app/galaxyvitals/data/protocol/EcgDisplayProcessor.kt`
- Create: `protocol/src/test/java/app/galaxyvitals/data/protocol/EcgDisplayProcessorTest.kt`
- Modify: `app/src/main/java/app/galaxyvitals/data/EcgRepository.kt`
- Modify: `app/src/main/java/app/galaxyvitals/ui/components/EcgWaveform.kt`
- Create: `app/src/test/java/app/galaxyvitals/ui/components/EcgWaveformReducerTest.kt`

**Interfaces:**
- Consumes: stored raw `EcgSample` values plus `srHz`, `signFactor`, and `polarityNormalized` from `EcgSession`.
- Produces: `EcgDisplayProcessor.filter(samples, srHz, signFactor, polarityNormalized): List<EcgSample>` and `reduceWaveform(samples, maxPoints): List<EcgSample>`.

- [ ] **Step 1: Write failing display-filter tests**

Use a signal containing a ramp, 1-Hz ECG-like component, and 80-Hz noise. Assert a copied filtered list is returned, source objects/values are unchanged, slow ramp and 80-Hz energy are attenuated, and effective polarity is applied once.

- [ ] **Step 2: Implement the GeminiMan-equivalent display path**

Apply a first-order 0.5-Hz high-pass followed by a first-order 40-Hz low-pass, initializing state from the first oriented sample to avoid a false startup spike. Preserve timestamps, HR, sample indices, and flags in copied `EcgSample` objects.

- [ ] **Step 3: Write and implement peak-preserving reduction**

For each time bucket, retain the minimum and maximum sample in chronological index order. Assert a one-sample QRS spike remains when 15,000 points are reduced to at most twice the canvas-width bucket count.

- [ ] **Step 4: Fix chart range order**

Select the visible zoom/pan slice first, reduce it for the canvas, then compute min/max and midpoint from that visible slice. Never derive the Y range from all 30 seconds when zoomed.

- [ ] **Step 5: Wire display-only filtering**

In `EcgRepository.loadSamples`, parse the canonical raw file and return the derived display list. Leave import, storage, export, quality, and inference paths on raw data.

- [ ] **Step 6: Run display tests**

Run `./gradlew :protocol:test --tests '*EcgDisplayProcessorTest' :app:testDebugUnitTest --tests '*EcgWaveformReducerTest'`. Expected: PASS.

### Task 5: Update packaging, duration, and user-facing model copy

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/app/galaxyvitals/ui/Formatters.kt`
- Modify: `app/src/test/java/app/galaxyvitals/ui/FormattersTest.kt`
- Modify: `app/src/main/java/app/galaxyvitals/ui/HealthTrackViewModel.kt`
- Modify: `app/src/main/java/app/galaxyvitals/ui/detail/EcgDetailScreen.kt`
- Modify: `app/src/main/java/app/galaxyvitals/ui/settings/SettingsScreen.kt`
- Modify: `README.md`

**Interfaces:**
- Consumes: Task 1 manifest and `EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID`.
- Produces: a release verification task named `verifyEcgNao3Bundle`, LiteRT dependency alias `libs.litert`, and truthful UI/docs for the new model.

- [ ] **Step 1: Replace runtime dependency and packaging rule**

Remove ONNX Runtime, add `com.google.ai.edge.litert:litert:2.1.3`, and change `noCompress` from `onnx` to `tflite`.

- [ ] **Step 2: Replace the release artifact verifier**

Read `ecg_nao3_bundle.json`, require exactly `model` and `filters` artifacts, prevent path escape from `src/main/assets`, and compare each SHA-256 before every release build.

- [ ] **Step 3: Fix duration rounding test-first**

Add a fixture with `durationSec=29.998`; expect `30s`. Implement with `durationSec.roundToInt()` rather than truncation.

- [ ] **Step 4: Remove stale ECGFounder claims**

Change analysis copy to “on-device N/A/O rhythm model”, retain the screening/not-a-medical-device warning, explain that the chart is display-filtered while the stored ECG remains raw, and remove the ECGFounder license/model export instructions from the current runtime section.

- [ ] **Step 5: Run formatting and release-bundle checks**

Run `./gradlew :app:testDebugUnitTest --tests '*FormattersTest' :app:verifyEcgNao3Bundle`. Expected: PASS.

### Task 6: Regression, build, and review

**Files:**
- Modify only files implicated by failures from Tasks 1–5.

**Interfaces:**
- Consumes: all prior tasks.
- Produces: passing phone/Wear builds plus a handoff that distinguishes host-verified behavior from target-device verification.

- [ ] **Step 1: Run the complete JVM regression suite**

Run `./gradlew :protocol:test :wear:testDebugUnitTest :app:testDebugUnitTest --stacktrace`. Expected: all tests PASS.

- [ ] **Step 2: Assemble both debug apps**

Run `./gradlew :app:assembleDebug :wear:assembleDebug --stacktrace`. Expected: phone and Wear debug APKs are produced under their module `build/outputs/apk/debug/` directories.

- [ ] **Step 3: Inspect packaged artifacts**

Use Android build outputs to confirm the phone APK contains `ecg/ecg_nao3_student_fp32.tflite` and does not contain `ecgfounder_1lead.onnx`, `onnxruntime`, FP16, or the rejected INT8 candidate.

- [ ] **Step 4: Review Samsung invariants**

Confirm the diff did not change `ECG_ON_DEMAND`, 500-Hz capture, `leadOff != 0`, raw mV storage, sensor timestamp storage, or tracker concurrency. Confirm display filtering is downstream only.

- [ ] **Step 5: Review repository hygiene**

Run `git status --short` and `git diff --check`. Ensure APK decompilation remains ignored and unrelated user files are untouched.

- [ ] **Step 6: Record the remaining device gate**

Report that LiteRT host invocation and FP16 parity passed, but Galaxy Watch 9/paired-phone target-device inference and visual capture could not be run because `adb devices -l` returned no device. Provide the two debug APK paths for installation and the exact validation scenario: 30 seconds with stable finger contact, chart without baseline collapse, quality not rejected solely for baseline drift, and a finite N/A/O score.

## Self-review

- Spec coverage: Samsung correctness, GeminiMan display difference, raw preservation, quality contradiction, polarity, duration, all three model placements, logits, LiteRT integration, old ONNX removal, tests, and device limitation each map to an explicit task.
- Placeholder scan: the plan contains no TBD/TODO placeholders or unspecified error-handling steps.
- Type consistency: `effectivePolarity`, `Nao3Preprocess.prepare`, `Nao3Postprocessor.fromLogits`, `EcgRhythmEngine.analyze`, `EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID`, `EcgDisplayProcessor.filter`, and `reduceWaveform` have one spelling and contract throughout.

# ECG Accuracy Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve Samsung ECG timing and quality evidence end-to-end, derive trustworthy ECG BPM, and fail closed when acquisition, preprocessing, or ECGFounder bundle integrity is uncertain.

**Architecture:** Capture immutable timestamped batches on Wear OS and serialize them as protocol v2 with content identity. Parse v1 compatibly but treat its synthetic timing as unverified; run SQI, continuous-window selection, ECG-derived BPM, and a hash-bound ECGFounder analysis bundle on the phone. Keep raw values independent from display/preprocessing polarity.

**Tech Stack:** Kotlin 2.1, Android/Wear OS, Samsung Health Sensor SDK, Compose, Room 2.7, Kotlin coroutines, ONNX Runtime, JUnit 4/Truth, Python/NumPy/SciPy/PyTorch, NeuroKit2 0.2.13 offline only.

**Spec:** User-provided “แผนปรับความแม่นยำ ECG แบบครบระบบ” in the 2026-08-20 Codex task.

## Global Constraints

- Preserve pre-existing uncommitted work, especially `WatchDataLayer.kt`, `WearNav.kt`, and related tests.
- Never run `HEART_RATE_CONTINUOUS` concurrently with `ECG_ON_DEMAND`.
- Preserve raw mV and sensor timestamps; polarity is metadata and a derived transform only.
- Demo recordings may be displayed but must never be classified by ECGFounder.
- NeuroKit2 0.2.13 is an offline reference only and is not device or clinical validation.
- No cloud upload, diagnostic claim, model replacement, or certification work.

---

### Task 1: Timestamped Samsung acquisition contract

**Files:**
- Modify: `wear/src/main/java/app/healthtrack/wear/sensors/EcgSensor.kt`
- Modify: `wear/src/main/java/app/healthtrack/wear/sensors/SamsungEcgSensor.kt`
- Modify: `wear/src/main/java/app/healthtrack/wear/sensors/DemoEcgSensor.kt`
- Create: `wear/src/test/java/app/healthtrack/wear/sensors/EcgBatchValidatorTest.kt`

**Interfaces:**
- Produces: `EcgBatch(samplesMv, sensorTimestampNs, sequence, leadOff, minThresholdMv, maxThresholdMv)` and `EcgSensorError`.
- Produces: independent `startEcg`, `stopEcg`, and `disconnect`; no HR dependency.

- [ ] Parse ECG metadata from the first `DataPoint`, require `leadOff == 0`, retain every point timestamp, and surface all tracker errors.
- [ ] Validate modulo-256 sequence continuity, monotonic timestamps, finite samples, and threshold saturation without modifying raw polarity.
- [ ] Dispatch sensor callbacks on a dedicated serial executor and make listener setup/teardown idempotent.
- [ ] Add mapper/validator tests for 5/10-point batches, lead-off values, saturation, wrap, gap, duplicate, and reversal.

### Task 2: Bounded 30-second recording lifecycle

**Files:**
- Modify: `wear/src/main/java/app/healthtrack/wear/capture/EcgSessionRecorder.kt`
- Modify: `wear/src/main/java/app/healthtrack/wear/ui/WearViewModels.kt`
- Modify: `wear/src/main/java/app/healthtrack/wear/ui/WearNav.kt`
- Modify: `wear/src/main/java/app/healthtrack/wear/ui/MeasureScreen.kt`
- Test: `wear/src/test/java/app/healthtrack/wear/capture/EcgSessionRecorderTest.kt`

**Interfaces:**
- Consumes: timestamped `EcgBatch`.
- Produces: immutable `EcgSessionSnapshot` with raw values, sample-relative sensor time, flags, capture source, and quality summary.

- [ ] Replace silently capped boxed lists with fixed primitive buffers that throw on overflow and reject invalid batches.
- [ ] Use a bounded contact preflight followed by a fresh 30-second ECG listener; stop it on success, error, timeout, navigation back, and background.
- [ ] Finish only near 15,000 samples with 29,998 ms sensor span and no fatal acquisition flags; throttle UI waveform publication to about 10 Hz.
- [ ] Add deterministic batching, missing-HR, timeout, back/background, contact-loss, saturation, and overflow tests.

### Task 3: Protocol v2 and content integrity

**Files:**
- Modify: `protocol/src/main/java/app/healthtrack/domain/EcgTypes.kt`
- Modify: `protocol/src/main/java/app/healthtrack/data/protocol/EcgCsvWriter.kt`
- Modify: `protocol/src/main/java/app/healthtrack/data/protocol/EcgCsvParser.kt`
- Modify: `protocol/src/main/java/app/healthtrack/data/protocol/EcgWearContract.kt`
- Modify: `wear/src/main/java/app/healthtrack/wear/sync/WatchDataLayer.kt`
- Modify: `app/src/main/java/app/healthtrack/data/wear/EcgWearListenerService.kt`
- Modify: `app/src/main/java/app/healthtrack/data/EcgRepository.kt`

**Interfaces:**
- Produces: v2 rows `(rel_ms, sample_index, ecg_raw_mv, flags)` and typed metadata/provenance.
- Produces: Data Layer `byteCount` and lowercase SHA-256; mismatches are not persisted or acknowledged.

- [ ] Add strict v2 metadata and v1 compatibility with `TimingTrust.ASSUMED` for legacy synthetic clocks.
- [ ] Preserve v2 payloads immutably and retain all metadata through parser/writer round trips.
- [ ] Hash/size payloads on watch and verify before phone persistence; quarantine same-session/different-hash collisions.
- [ ] Test v1/v2 round trips, one-byte mutation, truncation, idempotent same hash, and collision rejection.

### Task 4: Room v3 and domain status

**Files:**
- Modify: `app/src/main/java/app/healthtrack/data/local/EcgSessionEntity.kt`
- Modify: `app/src/main/java/app/healthtrack/data/local/AppDatabase.kt`
- Modify: `app/src/main/java/app/healthtrack/domain/EcgSession.kt`
- Modify: `app/src/main/java/app/healthtrack/data/EcgRepository.kt`
- Create: `app/src/androidTest/java/app/healthtrack/data/local/AppDatabaseMigrationTest.kt`

**Interfaces:**
- Produces fields `inputSchemaVersion`, `timingTrust`, `qualityStatus`, `cleanCoveragePct`, `qualityFlagsJson`, `ecgHrMedian`, `analysisBundleId`, and stale-result derivation.

- [ ] Add Room migration 2→3 with safe legacy defaults and retain prior results.
- [ ] Persist acquisition/SQI/BPM/bundle provenance without rewriting raw v2 files.
- [ ] Mark legacy timing and stale analysis explicitly.
- [ ] Test migration and entity/domain mappings.

### Task 5: SQI, continuous windows, and ECG BPM

**Files:**
- Create: `protocol/src/main/java/app/healthtrack/data/protocol/SignalQualityAnalyzer.kt`
- Create: `protocol/src/main/java/app/healthtrack/data/protocol/EcgBeatAnalyzer.kt`
- Modify: `protocol/src/main/java/app/healthtrack/data/protocol/EcgFounderPreprocess.kt`
- Create: `protocol/src/test/java/app/healthtrack/data/protocol/SignalQualityAnalyzerTest.kt`
- Create: `protocol/src/test/java/app/healthtrack/data/protocol/EcgBeatAnalyzerTest.kt`

**Interfaces:**
- Produces: `SignalQualityReport`, clean continuous segments/windows, detector agreement, and nullable median ECG BPM.

- [ ] Detect gaps/missing samples, held/flat runs, clipping, impulses, drift, 50/60-Hz mains, and high-frequency noise per recording/window.
- [ ] Never bridge gaps; support analysis only for 250/300/500 Hz and require three clean 10-second windows plus 20 seconds clean union.
- [ ] Replace short-recording zero padding; implement deterministic polyphase FIR for 250/300 Hz.
- [ ] Add Pan–Tompkins and Hamilton detectors and return BPM only when matched-peak agreement passes.
- [ ] Add clean/noisy/gap/clipping/mains/EMG/baseline and synthetic-R-peak tests.

### Task 6: Hash-bound ECGFounder analysis bundle

**Files:**
- Create: `app/src/main/assets/ecg/analysis_bundle.json`
- Modify: `app/src/main/assets/ecg/filters.json`
- Modify: `app/src/main/java/app/healthtrack/analysis/EcgFounderEngine.kt`
- Modify: `app/src/main/java/app/healthtrack/analysis/NaoCalibrator.kt`
- Modify: `app/build.gradle.kts`
- Modify: `tools/ecgfounder/export_ecgfounder.py`

**Interfaces:**
- Produces: verified bundle compatibility ID and fail-closed `AnalysisStatus` including `INDETERMINATE`.

- [ ] Bind model, labels, filters, calibrator, and decision thresholds by SHA-256 and compatibility ID.
- [ ] Verify hashes at release build and runtime; remove rule-head fallback and refuse `OK` on mismatch.
- [ ] Infer clean windows only, retain 150-output averaging, apply calibrated class thresholds, and abstain on weak consensus.
- [ ] Extend export parity to nonzero fixtures and generate record-disjoint proxy thresholds targeting 90% precision where attainable.

### Task 7: Accuracy-aware UI and export

**Files:**
- Modify: `app/src/main/java/app/healthtrack/ui/detail/EcgDetailScreen.kt`
- Modify: `app/src/main/java/app/healthtrack/ui/history/HistoryScreen.kt`
- Modify: `app/src/main/java/app/healthtrack/ui/HealthTrackViewModel.kt`
- Modify: `wear/src/main/java/app/healthtrack/wear/ui/MeasureScreen.kt`
- Create: `tools/ecgfounder/export_deidentified_validation.py`

**Interfaces:**
- Consumes: quality/BPM/analysis statuses and bundle identity.
- Produces: model-score wording, clean coverage/flags, ECG-derived BPM, legacy/stale warnings, and deidentified validation manifests.

- [ ] Hide N/A/O for low-quality or indeterminate recordings and label probability as “model score”.
- [ ] Display clean coverage, quality flags, ECG BPM, timing trust, and stale bundle state.
- [ ] Export deidentified participant-grouped manifests with provenance and no cloud transfer.

### Task 8: Golden parity and regression gate

**Files:**
- Modify: `protocol/src/test/java/app/healthtrack/data/protocol/EcgFounderPreprocessTest.kt`
- Create: `tools/ecgfounder/generate_golden_fixtures.py`
- Modify: `tools/ecgfounder/compare_nao_founder.py`
- Modify: `tools/ecgfounder/COMPARE_RESULTS.md`
- Modify: `PROTOCOL.md`

**Interfaces:**
- Produces: reproducible 250/300/500-Hz filter/resample fixtures and documented proxy-vs-clinical boundary.

- [ ] Pin SciPy/NeuroKit2 fixture generation and assert Python↔Kotlin correlation ≥0.999 and max absolute difference ≤1e-4.
- [ ] Assert Android ONNX/calibrated/final outputs within 1e-4 on nonzero fixtures.
- [ ] Keep pre-abstention proxy metrics within 0.01 of macro-F1 0.789, AF AUROC 0.966, and O recall 0.65; report post-abstention precision/recall/coverage including noisy records.
- [ ] Update protocol and run `:protocol:test :wear:testDebugUnitTest :app:testDebugUnitTest`.

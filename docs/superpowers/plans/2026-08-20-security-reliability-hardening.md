# GalaxyBridge Security and Reliability Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent untrusted ECG imports from exhausting memory or escaping the inbox, preserve user recordings across upgrades and sync, and remove confirmed Android/Wear lifecycle and build-supply-chain defects.

**Architecture:** Treat external intents and Wear Data Layer payloads as trust boundaries. Centralize session-ID validation and streaming parser limits in `:protocol`, make phone/watch persistence atomic and serialized, and scope long-lived sensor work to its navigation destination. Preserve the new watch-history behavior by marking acknowledged files as synced instead of deleting pending data.

**Tech Stack:** Kotlin 2.1, Android/Wear OS, Compose, Navigation 3, Room 2.7, Kotlin coroutines, Play Services Wearable, JUnit 4/Truth, Python/PyTorch/NumPy, Gradle 8.13.

**Spec:** `README.md`, `PROTOCOL.md`, and the 2026-08-20 user request to review and fix security vulnerabilities and likely bugs.

## Global Constraints

- Preserve all pre-existing uncommitted work in the dirty worktree.
- Keep phone and watch `applicationId` equal to `app.healthtrack` so Data Layer delivery continues to work.
- Keep ECG capture at 500 Hz and 30 seconds; parser compatibility may accept bounded non-default sample rates.
- Do not add network access; model inference remains on-device.
- Do not silently discard unsynced ECG recordings.
- Every trust-boundary fix must have a regression test where a JVM-testable seam exists.

---

### Task 1: Constrain session identifiers and ECG parsing

**Files:**
- Modify: `protocol/src/main/java/app/healthtrack/data/protocol/EcgWearContract.kt`
- Modify: `protocol/src/main/java/app/healthtrack/data/protocol/EcgCsvParser.kt`
- Modify: `protocol/src/test/java/app/healthtrack/data/protocol/EcgCsvParserTest.kt`

**Interfaces:**
- Produces: `EcgWearContract.requireSessionId(String): String`, `sanitizeSessionId(String, String): String`, and inbox/path builders that reject separators, traversal tokens, blank IDs, and IDs longer than 80 characters.
- Produces: streaming parser limits `MAX_COMPRESSED_BYTES`, `MAX_UNCOMPRESSED_BYTES`, `MAX_SAMPLES`, `MAX_DURATION_MS`, and `parseAutoStream(InputStream, String)`.

- [ ] **Step 1: Add failing identifier tests**

Add assertions that `requireSessionId("1700")` succeeds, `inboxFileName("../escape")` and IDs containing `/` or `\\` throw, and `sanitizeSessionId("ecg_bad/../../42.csv.gz", "import")` returns a valid single segment.

- [ ] **Step 2: Add failing parser resource and numeric tests**

Build test inputs for `sr_hz=-1`, `sr_hz=2147483647`, more than `MAX_SAMPLES` rows, decreasing or overlong `rel_ms`, `NaN`/`Infinity` amplitudes, an oversized decompressed gzip, and a valid gzip passed through `parseAutoStream`. Assert invalid inputs throw `EcgParseException` without allocating from attacker-controlled metadata.

- [ ] **Step 3: Run the focused tests and confirm failure**

Run: `.\gradlew.bat :protocol:test --tests "*EcgCsvParserTest"`

Expected: the new validation/limit assertions fail against the current unbounded parser.

- [ ] **Step 4: Implement strict IDs and bounded streaming**

Validate IDs with `[A-Za-z0-9][A-Za-z0-9._-]{0,79}`; sanitize imports from the final path segment only. Wrap raw gzip input at 8 MiB and decompressed/plain input at 16 MiB, cap rows/samples at 30,000, validate sample rate `1..2000`, cap duration at 120 seconds, require nondecreasing nonnegative timestamps, ignore non-finite amplitudes, and treat out-of-range heart rates as missing. Make `EcgParseException` an `IOException` so limit failures propagate through stream APIs.

- [ ] **Step 5: Run protocol tests**

Run: `.\gradlew.bat :protocol:test`

Expected: all parser, writer, preprocessing, and label tests pass.

### Task 2: Secure phone imports and Wear asset ingestion

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/data_extraction_rules.xml`
- Modify: `app/src/main/java/app/healthtrack/MainActivity.kt`
- Modify: `app/src/main/java/app/healthtrack/data/EcgRepository.kt`
- Modify: `app/src/main/java/app/healthtrack/data/wear/EcgWearListenerService.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/test/java/app/healthtrack/data/UserFacingAnalysisErrorTest.kt`

**Interfaces:**
- Consumes: Task 1 strict identifiers and `parseAutoStream`.
- Produces: bounded, unique imports; atomic inbox writes; nonblocking asset retrieval; explicit confirmation for external share/view intents.

- [ ] **Step 1: Add failing error-mapping tests**

Assert size-limit, invalid-metadata, and generic provider exceptions map to fixed user-facing text and never expose raw filesystem/provider messages.

- [ ] **Step 2: Replace whole-file import and overwrite behavior**

Parse the `ContentResolver` stream directly with Task 1 limits, sanitize the display name, and allocate a unique ID when the Room primary key already exists. Serialize imports/ingests with a coroutine `Mutex`, write canonical gzip through `AtomicFile`, remove the unnecessary persistable URI grant, and avoid re-importing the launch intent on configuration recreation.

- [ ] **Step 3: Confirm external imports and reduce manifest exposure**

Stage `ACTION_SEND`/`ACTION_VIEW` URIs in `MainActivity` and show an `AlertDialog` before calling `viewModel.importUri`. Restrict share MIME types to gzip/octet-stream/CSV, remove unused `INTERNET`, remove `directBootAware`, add extraction rules excluding app-private health data from cloud/device transfer, and keep only path-filtered Wear listener actions.

- [ ] **Step 4: Make Data Layer ingestion asynchronous and atomic**

Validate the path ID, DataMap `sessionId`, and `format`; launch asset fetching on the application IO scope; replace callback-thread `runBlocking` with suspend `withTimeout`; cap copied compressed bytes; persist via `AtomicFile`; and set received notifications to secret visibility without a timestamp/session ID in lock-screen text.

- [ ] **Step 5: Run phone tests and compilation**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`

Expected: tests pass and the phone APK compiles with no blocking listener code.

### Task 3: Preserve databases during the v1-to-v2 upgrade

**Files:**
- Modify: `app/src/main/java/app/healthtrack/data/local/AppDatabase.kt`

**Interfaces:**
- Produces: `MIGRATION_1_2`, adding `analysisStatus`, `naoLabel`, `naoConfidence`, `findings`, and `analysisNote` with defaults matching `EcgSessionEntity`.

- [ ] **Step 1: Define the exact migration**

Add five `ALTER TABLE ecg_sessions ADD COLUMN` statements: non-null text columns use `DEFAULT 'NONE'` or `DEFAULT ''`; label/confidence columns remain nullable.

- [ ] **Step 2: Remove destructive fallback**

Register `MIGRATION_1_2` with `Room.databaseBuilder(...).addMigrations(...)` and remove `fallbackToDestructiveMigration`, ensuring existing recordings survive the schema upgrade.

- [ ] **Step 3: Compile Room schema validation**

Run: `.\gradlew.bat :app:kspDebugKotlin :app:assembleDebug`

Expected: Room-generated implementation compiles and the migration column types/defaults match the entity.

### Task 4: Fix phone state races and locale-dependent persistence

**Files:**
- Modify: `app/src/main/java/app/healthtrack/ui/HealthTrackViewModel.kt`
- Modify: `protocol/src/main/java/app/healthtrack/data/protocol/EcgFounderLabels.kt`
- Modify: `protocol/src/test/java/app/healthtrack/data/protocol/EcgFounderPreprocessTest.kt`
- Modify: `app/src/main/java/app/healthtrack/analysis/EcgFounderEngine.kt`

**Interfaces:**
- Produces: cancel-and-replace sample loading, atomic busy transitions, locale-invariant findings, validated model output.

- [ ] **Step 1: Add a locale regression test**

Temporarily set a comma-decimal default locale, encode/decode `LabeledScore("ATRIAL FIBRILLATION", 0.812f)`, restore the locale in `finally`, and assert the score round-trips.

- [ ] **Step 2: Fix ViewModel operation races**

Set `busy=true` synchronously before launching import/demo/sync work, rethrow `CancellationException`, reset busy in `finally`, and keep a `Job` for sample loading. Cancel the previous job and clear samples before loading a different session so recording A cannot flash or complete after recording B.

- [ ] **Step 3: Harden model resource handling**

Close `OrtSession.SessionOptions` after session creation, atomically replace copied model assets, and reject output arrays whose length differs from 150 or contains non-finite values before aggregation.

- [ ] **Step 4: Run focused tests**

Run: `.\gradlew.bat :protocol:test :app:testDebugUnitTest`

Expected: locale serialization and existing analysis-error tests pass.

### Task 5: Make Wear sync durable without losing local history

**Files:**
- Modify: `wear/src/main/java/app/healthtrack/wear/store/WatchEcgStore.kt`
- Modify: `wear/src/main/java/app/healthtrack/wear/sync/WatchWearListenerService.kt`
- Modify: `wear/src/main/java/app/healthtrack/wear/sync/WatchDataLayer.kt`
- Modify: `wear/src/main/java/app/healthtrack/wear/sync/SyncInboxWorker.kt`
- Modify: `wear/src/test/java/app/healthtrack/wear/PhoneWatchBridgeTest.kt`
- Modify: `PROTOCOL.md`

**Interfaces:**
- Consumes: Task 1 strict identifiers and size constants.
- Produces: `.synced` acknowledgement markers, `listPendingGzipFiles()`, atomic capped save, and retention that prunes only acknowledged files.

- [ ] **Step 1: Add failing store/sync tests**

Use a file-backed test constructor for `WatchEcgStore`. Save multiple sessions, mark one exact ID synced, assert only unsynced files are returned for upload, assert a new save clears a stale marker, and assert retention never deletes pending files.

- [ ] **Step 2: Implement exact acknowledgements**

On `/ecg/cleanup/{id}`, strictly validate the ID, create an adjacent `.synced` marker, and prune oldest synced records only when history exceeds eight. Keep pending records regardless of the history cap. Upload only `listPendingGzipFiles()` so acknowledged sessions are not resurrected on every sync.

- [ ] **Step 3: Coalesce sync work and preserve cancellation**

Enqueue `SyncInboxWorker` as unique work with `ExistingWorkPolicy.KEEP`; in `CoroutineWorker`, rethrow `CancellationException` and retry only actual failures.

- [ ] **Step 4: Document the acknowledgement contract**

Update `PROTOCOL.md`: cleanup marks the exact watch copy synced, acknowledged history is capped at eight, and unsynced files are never pruned.

- [ ] **Step 5: Run Wear bridge tests**

Run: `.\gradlew.bat :wear:testDebugUnitTest`

Expected: bridge, recorder, pending/synced, and retention tests pass.

### Task 6: Fix Wear sensor and navigation lifecycles

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `wear/build.gradle.kts`
- Create: `wear/src/main/res/xml/data_extraction_rules.xml`
- Modify: `wear/src/main/AndroidManifest.xml`
- Modify: `wear/src/main/java/app/healthtrack/wear/ui/WearNav.kt`
- Modify: `wear/src/main/java/app/healthtrack/wear/ui/WearViewModels.kt`
- Modify: `wear/src/main/java/app/healthtrack/wear/sensors/OffBodyMonitor.kt`
- Modify: `wear/src/main/java/app/healthtrack/wear/capture/EcgSessionRecorder.kt`

**Interfaces:**
- Produces: destination-scoped ViewModels, cancellable demo startup, bounded recorder memory, delayed off-body confirmation, and disconnect-on-timeout/completion.

- [ ] **Step 1: Scope ViewModels to Navigation 3 entries**

Add `androidx.lifecycle:lifecycle-viewmodel-navigation3:2.10.0`, then pass both `rememberSaveableStateHolderNavEntryDecorator()` and `rememberViewModelStoreNavEntryDecorator()` to `NavDisplay`. Popping Measure must call `MeasureViewModel.onCleared`, stopping sensors and the foreground service.

- [ ] **Step 2: Fix delayed and stale sensor work**

Track/cancel the delayed demo-start job, disconnect the singleton Samsung sensor on connection timeout or failure, perform finish/save work on `Dispatchers.IO`, and disconnect after completion. Cap recorder samples at 32 seconds of 500 Hz data.

- [ ] **Step 3: Make off-body debounce actually fire**

Schedule a main-thread callback for `OFF_BODY_BLOCK_MS` when the sensor first reports off-body, cancel it on contact/stop, and reset `off` plus `lastOffAt` between sessions so a transition-only sensor still aborts after 1.8 seconds.

- [ ] **Step 4: Remove pre-unlock health-data access**

Remove `directBootAware` and broad `BIND_LISTENER`, add data-extraction exclusions, and simplify the always-true API 33 permission branch.

- [ ] **Step 5: Run Wear tests and build**

Run: `.\gradlew.bat :wear:testDebugUnitTest :wear:assembleDebug`

Expected: tests pass and the Wear APK compiles with entry-scoped ViewModels.

### Task 7: Harden dependency resolution and offline ML tools

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `wear/build.gradle.kts`
- Modify: `gradle/wrapper/gradle-wrapper.properties`
- Modify: `tools/ecgfounder/export_ecgfounder.py`
- Modify: `tools/ecgfounder/train_nao_head.py`

**Interfaces:**
- Produces: Fragment 1.8.9 pin over Play Services' vulnerable/obsolete 1.1.0 transitive version, verified Gradle distribution, safe tensor-only checkpoint loading, non-pickle NumPy caches, and strict state-dict compatibility.

- [ ] **Step 1: Pin AndroidX Fragment**

Declare `androidx.fragment:fragment:1.8.9` in both apps so Play Services cannot resolve Fragment 1.1.0; this removes the Activity Result lint errors without suppressing them.

- [ ] **Step 2: Verify the Gradle distribution**

Add `distributionSha256Sum=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`, the official Gradle 8.13 binary checksum.

- [ ] **Step 3: Remove executable deserialization defaults**

Change `torch.load(..., weights_only=True)`, fail export if any model keys are missing/unexpected, and change `np.load(..., allow_pickle=False)`. Keep numeric `.npz` cache compatibility.

- [ ] **Step 4: Run Python static smoke checks**

Run: `py -m py_compile tools/ecgfounder/export_ecgfounder.py tools/ecgfounder/train_nao_head.py tools/ecgfounder/compare_nao_founder.py`

Expected: all scripts compile without executing model downloads or untrusted artifacts.

### Task 8: Full verification and final review

**Files:**
- Review: all modified files

**Interfaces:**
- Consumes: Tasks 1-7.
- Produces: passing tests/build/lint and a concise vulnerability/fix report.

- [ ] **Step 1: Run the full verification suite**

Run: `.\gradlew.bat --no-parallel :protocol:test :app:testDebugUnitTest :wear:testDebugUnitTest :app:assembleDebug :wear:assembleDebug :app:lintDebug :wear:lintDebug`

Expected: `BUILD SUCCESSFUL`; lint may retain non-security cosmetic warnings but no errors.

- [ ] **Step 2: Run repository hygiene checks**

Run: `git diff --check` and `git status --short`

Expected: no whitespace errors; all pre-existing dirty files remain present and recognizable.

- [ ] **Step 3: Review security invariants**

Confirm every filesystem path passes a strict ID builder, every external byte stream has compressed/decompressed/sample/time limits, pending watch data is never pruned, Room no longer destructively migrates, cancellation is not swallowed, and no new network permission/dependency was introduced.

- [ ] **Step 4: Report findings and residual risks**

Summarize fixed severities, tests run, and remaining device-only checks: Samsung privileged sensor behavior, actual paired-watch Data Layer delivery, notification appearance, and v1 database migration on a device/emulator image.

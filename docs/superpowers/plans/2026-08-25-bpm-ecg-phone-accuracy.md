# BPM and ECG Model Phone Accuracy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep ECG-derived BPM even when the on-device NAO3 model fails, make the packaged filter/model bundle hash-stable across Windows/Unix checkouts, and auto-repair stale phone history after install.

**Architecture:** Phone analysis stays acquisition → quality/BPM → model. BPM and quality are computed first and persisted on every terminal result. Only NAO3 preprocessing and Interpreter work sit inside try/catch. Asset JSON is LF-normalized and SOS-complete so APK hashes match the bundle. Existing Room columns (`analysisStatus`, `analysisBundleId`, `ecgHrMedian`) drive one-shot auto-repair of old rows.

**Tech Stack:** Kotlin 2.1 / JVM 17, Android 32+, LiteRT 2.1.3 FP32 CPU, Room 2.7.2, JUnit 4, Truth, AndroidX Test core/runner 1.7.0, ext:junit 1.3.0.

**Spec:** `C:\Users\foxka\Downloads\PLAN (1).md`

## Global Constraints

- Phone analysis/build pipeline only. Do not change Wear acquisition.
- Keep FP32 LiteRT `2.1.3` on CPU. Do not change the model, quantize, or add a GPU delegate.
- Keep schema v2 and leave `hr_bpm` empty; BPM comes from ECG, not a second HR sensor.
- No new Room columns or migrations. Database stays at version 3.
- Do not modify or delete original ECG waveform files during repair.
- Do not log raw ECG samples or millivolt values.
- Failure of the model must be `AnalysisStatus.FAILED` with no N/A/O decision, while still storing `ecgHrMedian`, quality, and the current bundle ID.
- Filter artifact SHA-256 after adding missing `a0 = 1.0` and LF newlines is exactly `1c72ace362fdff4ce0dd8d3ac0cbc7e898300a0b866fb53ef48cdc6e6d95dd52`.
- Bundle `compatibility_id` remains `ecg-nao3-student-256hz-v1`.
- Model SHA-256 remains `7400a2352c79275d5a4860a76a684cc0b6140e8385572de5a68027f7343a20ac`.

---

### Task 1: Make ECG assets platform-stable and verify SOS at preBuild

**Files:**
- Create: `.gitattributes`
- Modify: `app/src/main/assets/ecg/ecg_nao3_filters_256hz.json`
- Modify: `app/src/main/assets/ecg/ecg_nao3_bundle.json`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: current NAO3 filter JSON (third SOS row is missing `a0`) and bundle hash `0e894310042206048a13d109bfa34c3cb05bf7de6ebcb692039c526f7ffdcf6b`.
- Produces: LF-normalized JSON assets; third SOS row `[1.0, -2.0, 1.0, 1.0, -1.9877958220371288, 0.987947188791069]`; filters SHA-256 `1c72ace362fdff4ce0dd8d3ac0cbc7e898300a0b866fb53ef48cdc6e6d95dd52`; `verifyEcgNao3Bundle` hooked to `preBuild` for debug and release, asserting 5 SOS rows of 6 finite coefficients each.

- [ ] **Step 1: Add `.gitattributes`**

```gitattributes
app/src/main/assets/ecg/*.json text eol=lf
app/src/main/assets/ecg/*.tflite binary
```

- [ ] **Step 2: Insert the missing `a0 = 1.0` in SOS row three**

The third SOS array currently has five numbers:

```json
    [
      1.0,
      -2.0,
      1.0,
      -1.9877958220371288,
      0.987947188791069
    ],
```

Change it to six numbers matching `Nao3Preprocess` (do not edit `Nao3Preprocess.kt`):

```json
    [
      1.0,
      -2.0,
      1.0,
      1.0,
      -1.9877958220371288,
      0.987947188791069
    ],
```

Keep the rest of the file byte-identical except this insertion. Save with LF newlines only (no CR).

- [ ] **Step 3: Update the bundle filter hash**

In `app/src/main/assets/ecg/ecg_nao3_bundle.json` set:

```json
"sha256": "1c72ace362fdff4ce0dd8d3ac0cbc7e898300a0b866fb53ef48cdc6e6d95dd52"
```

Save with LF newlines only.

- [ ] **Step 4: Hook `verifyEcgNao3Bundle` to `preBuild` and validate SOS shape**

Replace the current `preReleaseBuild`-only hook:

```kotlin
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyEcgNao3Bundle)
}
```

with:

```kotlin
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(verifyEcgNao3Bundle)
}
```

Inside `verifyEcgNao3Bundle.doLast`, after the existing artifact hash checks, parse `src/main/assets/ecg/ecg_nao3_filters_256hz.json` and require:

- `sos` is a list of exactly 5 rows
- each row has exactly 6 coefficients
- every coefficient is a finite number (`Number` that is not NaN/Infinity)

Throw `GradleException` with a clear message if any check fails.

- [ ] **Step 5: Renormalize and verify**

```powershell
git add --renormalize app/src/main/assets/ecg/*.json
git ls-files --eol -- app/src/main/assets/ecg/
.\gradlew :app:verifyEcgNao3Bundle --quiet
```

Expected: JSON files show `w/lf` (or `i/lf` with working-tree LF). Task succeeds. Filter file SHA-256 of the on-disk LF bytes is `1c72ace362fdff4ce0dd8d3ac0cbc7e898300a0b866fb53ef48cdc6e6d95dd52`.

- [ ] **Step 6: Commit**

```powershell
git add .gitattributes app/src/main/assets/ecg/ecg_nao3_filters_256hz.json app/src/main/assets/ecg/ecg_nao3_bundle.json app/build.gradle.kts
git commit -m "fix: LF-normalize NAO3 filter SOS and verify the bundle on preBuild"
```

---

### Task 2: Keep ECG BPM when the NAO3 model fails

**Files:**
- Create: `app/src/main/java/app/galaxyvitals/analysis/EcgRhythmAnalysis.kt`
- Create: `app/src/test/java/app/galaxyvitals/analysis/EcgRhythmAnalysisTest.kt`
- Create: `app/src/test/java/app/galaxyvitals/analysis/EcgAnalysisFixtures.kt` (test-only synthetic 72 BPM / low-quality helpers)
- Modify: `app/src/main/java/app/galaxyvitals/analysis/EcgRhythmEngine.kt`
- Modify: `app/src/main/java/app/galaxyvitals/data/EcgRepository.kt`
- Modify: `app/src/test/java/app/galaxyvitals/ui/FormattersTest.kt`
- Modify: `app/src/test/java/app/galaxyvitals/data/UserFacingAnalysisErrorTest.kt` if log-message helper lives next to existing error mappers

**Interfaces:**
- Consumes: `EcgFounderPreprocess.prepare`, `EcgBeatAnalyzer.analyze`, `Nao3Preprocess.prepare`, `AnalysisResult`.
- Produces:
  - `enum class ModelFailureStage { BUNDLE_LOAD, MODEL_PREPROCESS, INTERPRETER_INIT, INFERENCE }`
  - `class ModelAnalysisException(val stage: ModelFailureStage, cause: Throwable) : RuntimeException(cause)`
  - `data class AnalysisResult(..., val failureStage: ModelFailureStage? = null, val cause: Throwable? = null)`
  - `object EcgRhythmAnalysis { fun analyze(parsed: ParsedEcgFile, classify: (FloatArray) -> NaoDecision): AnalysisResult }`
  - `internal fun successfulResult(...)` and `internal fun failedModelResult(...)` as specified below
  - `internal fun analysisFailureLogMessage(stage: ModelFailureStage?, bundleId: String?, error: Throwable): String` containing stage, bundle id, and error class/message but never waveform samples

- [ ] **Step 1: Write the failing tests first (TDD)**

In `EcgRhythmAnalysisTest.kt`:

1. `throwingClassifierStillReturnsFailedWithBpmNear72` — 30 s clean 72 BPM synthetic ECG; `classify` throws `RuntimeException("boom")`; assert `status == FAILED`, `decision == null`, `ecgHrMedian` within 3 of 72, quality non-null, `analysisBundleId == EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID`, `failureStage == INFERENCE` (or the stage the classify exception maps to), `cause != null`.
2. `lowQualityDoesNotCallClassifier` — recording that fails the quality gate (flatline / too few samples); classifier is a lambda that sets an `AtomicBoolean` and throws if called; assert `status == LOW_QUALITY`, classifier never called, `ecgHrMedian` may be null or a number but quality is present, bundle id is current.
3. `successfulClassifierReturnsBpmQualityAndNao` — clean 72 BPM ECG; classifier returns `NaoDecision(NaoLabel.N, 0.9f, 0.9f, 0.05f, 0.05f, emptyList())` without throwing; assert `status == OK`, `decision!!.label == N`, `ecgHrMedian` within 3 of 72, quality usable, bundle id current, `failureStage == null`.

In `FormattersTest.kt` add:

4. `failedAnalysisStillShowsEcgBpmOnHistory` — session with `AnalysisStatus.FAILED`, `naoLabel = null`, `ecgHrMedian = 81.2`; assert `hrLabel() == "81"` and `naoTitle() == "Not analysed"`.

Add a test that `analysisFailureLogMessage` includes the stage name and bundle id and does **not** contain millivolt substrings even if the throwable message tries to include `"0.12mV"`.

Do **not** implement production code yet. Run:

```powershell
.\gradlew :app:testDebugUnitTest --tests app.galaxyvitals.analysis.EcgRhythmAnalysisTest --tests app.galaxyvitals.ui.FormattersTest
```

Expected: compile/test failure because `EcgRhythmAnalysis` / new helpers do not exist, or the FAILED+BPM assertion is missing.

- [ ] **Step 2: Implement `EcgRhythmAnalysis` exactly as specified**

```kotlin
internal fun successfulResult(
    decision: NaoDecision,
    quality: SignalQualityReport,
    ecgHrMedian: Double?,
    note: String,
): AnalysisResult = AnalysisResult(
    status = AnalysisStatus.OK,
    decision = decision,
    note = note,
    quality = quality,
    ecgHrMedian = ecgHrMedian,
    analysisBundleId = EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID,
)

internal fun failedModelResult(
    quality: SignalQualityReport?,
    ecgHrMedian: Double?,
    bundleId: String,
    cause: Throwable,
    stage: ModelFailureStage,
): AnalysisResult = AnalysisResult(
    status = AnalysisStatus.FAILED,
    decision = null,
    note = userFacingAnalysisError(cause),
    quality = quality,
    ecgHrMedian = ecgHrMedian,
    analysisBundleId = bundleId,
    failureStage = stage,
    cause = cause,
)
```

`EcgRhythmAnalysis.analyze`:

```kotlin
val prepared = EcgFounderPreprocess.prepare(parsed)
val beat = EcgBeatAnalyzer.analyze(parsed, prepared)
if (!prepared.quality.usableForAnalysis) {
    return AnalysisResult(
        status = AnalysisStatus.LOW_QUALITY,
        decision = null,
        note = "Low ECG quality: ${prepared.quality.flags.joinToString { it.name }}",
        quality = prepared.quality,
        ecgHrMedian = beat.bpmMedian,
        analysisBundleId = EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID,
    )
}
return try {
    val input = try {
        Nao3Preprocess.prepare(parsed)
    } catch (error: Exception) {
        throw ModelAnalysisException(ModelFailureStage.MODEL_PREPROCESS, error)
    }
    val decision = classify(input)
    successfulResult(decision, prepared.quality, beat.bpmMedian, qualityNote(prepared.quality, prepared.windows.size))
} catch (error: Exception) {
    val stage = (error as? ModelAnalysisException)?.stage ?: ModelFailureStage.INFERENCE
    failedModelResult(
        quality = prepared.quality,
        ecgHrMedian = beat.bpmMedian,
        bundleId = EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID,
        cause = error,
        stage = stage,
    )
}
```

Do not wrap quality/BPM in that try/catch.

- [ ] **Step 3: Wire `EcgRhythmEngine` stages around bundle/interpreter/inference**

`EcgRhythmEngine.analyze(parsed)` must call `EcgRhythmAnalysis.analyze(parsed, ::infer)`.

`infer` / lazy bundle / `createInterpreter` must throw `ModelAnalysisException` with:

- `BUNDLE_LOAD` when `EcgAnalysisBundle.load` or asset mapping fails
- `INTERPRETER_INIT` when `Interpreter` construction / tensor allocate / shape checks fail
- `INFERENCE` when `Interpreter.run` or postprocess fails

Do not let those exceptions escape `analyze()` — `EcgRhythmAnalysis` converts them to `FAILED` with BPM.

- [ ] **Step 4: Persist partial results and log without waveform**

In `EcgRepository.analyze`, copy `result.ecgHrMedian`, `result.quality`, and `result.analysisBundleId` for every engine result including `FAILED`. When `status == FAILED`, call `Log.e("GalaxyVitalsEcg", analysisFailureLogMessage(...), result.cause)` — never put samples, millivolts, or CSV text in the log string.

`failedAnalysis` (parse / missing-file path) must also set `analysisBundleId = EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID` so a terminal failure is not retried forever, and must `Log.e` the same way. Keep `naoLabel`/`naoConfidence` null.

Do not change schema v2 writers. Do not add an HR sensor field.

- [ ] **Step 5: Re-run unit tests and commit**

```powershell
.\gradlew :app:testDebugUnitTest
```

Expected: pass.

```powershell
git add app/src/main/java/app/galaxyvitals/analysis app/src/main/java/app/galaxyvitals/data/EcgRepository.kt app/src/test/java/app/galaxyvitals
git commit -m "fix: keep ECG BPM and quality when the NAO3 model fails"
```

---

### Task 3: Auto-repair stale history on app start

**Files:**
- Create: `app/src/main/java/app/galaxyvitals/data/StaleEcgRepair.kt`
- Create: `app/src/test/java/app/galaxyvitals/data/StaleEcgRepairTest.kt`
- Modify: `app/src/main/java/app/galaxyvitals/data/local/EcgSessionDao.kt`
- Modify: `app/src/main/java/app/galaxyvitals/data/EcgRepository.kt`
- Modify: `app/src/main/java/app/galaxyvitals/GalaxyVitalsApp.kt`

**Interfaces:**
- Consumes: existing `ingestMutex`, `reanalyze` internals, `EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID`.
- Produces:
  - `fun StaleEcgRepair.isStale(status: String, bundleId: String?, currentBundleId: String): Boolean`
  - `fun StaleEcgRepair.ordered(rows: List<EcgSessionEntity>, currentBundleId: String): List<EcgSessionEntity>` newest `tsStartMs` first
  - DAO `suspend fun listStaleSessions(currentBundleId: String): List<EcgSessionEntity>`
  - `suspend fun EcgRepository.reanalyzeStaleSessions(): Int`

- [ ] **Step 1: Write failing tests (TDD)**

`StaleEcgRepairTest`:

1. `selectsNonePendingNullBundleAndMismatchedBundle` — mix of rows: `OK`+current, `FAILED`+null, `NONE`, `PENDING`, `OK`+old bundle, `LOW_QUALITY`+current, `FAILED`+current. Only the stale ones are returned: `NONE`, `PENDING`, `FAILED`+null, `OK`+old. `LOW_QUALITY`+current and `FAILED`+current and `OK`+current are excluded.
2. `ordersNewestFirst` — two stale rows with `tsStartMs` 10 and 20; result order is 20 then 10.
3. `secondPassSkipsTerminalCurrentBundleRows` — start with stale rows; after mapping each through a fake analyze that writes `FAILED`/`OK` plus current bundle id, `ordered(...)` on the outputs is empty. Also assert the fake analyze was invoked once per stale row, and invoking `ordered` again does not produce those ids.

A row is stale iff `analysisStatus` is `NONE` or `PENDING`, **or** `analysisBundleId IS NULL`, **or** `analysisBundleId` is not the current compatibility id.

- [ ] **Step 2: Implement selection + DAO query**

```kotlin
@Query(
    """
    SELECT * FROM ecg_sessions
    WHERE analysisStatus IN ('NONE', 'PENDING')
       OR analysisBundleId IS NULL
       OR analysisBundleId != :currentBundleId
    ORDER BY tsStartMs DESC
    """,
)
suspend fun listStaleSessions(currentBundleId: String): List<EcgSessionEntity>
```

No schema version bump.

- [ ] **Step 3: Implement `reanalyzeStaleSessions()`**

Must:

- Run on `Dispatchers.IO`
- Take `ingestMutex` for the whole batch so Wear ingest cannot interleave
- Load stale rows via the DAO (newest first)
- For each row: `dao.upsert(row.copy(analysisStatus = PENDING.name))` **before** parsing/analyzing
- Parse the existing file and call the same `analyze` / `failedAnalysis` path as `reanalyze`
- Never delete, rewrite, or rename the ECG file
- Return the number of rows processed
- Not call `reanalyze()` (that function also takes the mutex — deadlock)

- [ ] **Step 4: Launch after purge + inbox, off the UI thread**

In `GalaxyVitalsApp.onCreate`:

```kotlin
appScope.launch {
    container.ecgRepository.purgeDemoData()
    container.ecgRepository.ingestPendingInbox()
    container.ecgRepository.reanalyzeStaleSessions()
}
```

Keep `appScope` as `SupervisorJob() + Dispatchers.IO`. Do not block `onCreate`.

- [ ] **Step 5: Tests, then commit**

```powershell
.\gradlew :app:testDebugUnitTest --tests app.galaxyvitals.data.StaleEcgRepairTest
.\gradlew :app:testDebugUnitTest
```

```powershell
git add app/src/main/java/app/galaxyvitals/data app/src/main/java/app/galaxyvitals/data/local/EcgSessionDao.kt app/src/main/java/app/galaxyvitals/GalaxyVitalsApp.kt app/src/test/java/app/galaxyvitals/data/StaleEcgRepairTest.kt
git commit -m "feat: reanalyze stale ECG sessions after inbox ingest"
```

---

### Task 4: Device smoke test and build gates

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/androidTest/java/app/galaxyvitals/analysis/EcgPackagedBundleSmokeTest.kt`
- Create or reuse: androidTest fixture helper for a 30 s 72 BPM `ParsedEcgFile`

**Interfaces:**
- Consumes: packaged `EcgAnalysisBundle` + `EcgRhythmEngine` on a real device/emulator.
- Produces: instrumented smoke test using AndroidX Test core **1.7.0**, runner **1.7.0**, ext:junit **1.3.0**.

- [ ] **Step 1: Add AndroidX Test 1.7.0 / ext:junit 1.3.0**

In `libs.versions.toml`:

```toml
androidxTestCore = "1.7.0"
androidxTestRunner = "1.7.0"
androidxTestExtJunit = "1.3.0"
```

```toml
androidx-test-core = { group = "androidx.test", name = "core", version.ref = "androidxTestCore" }
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "androidxTestRunner" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestExtJunit" }
```

In `app/build.gradle.kts` dependencies:

```kotlin
androidTestImplementation(libs.androidx.test.core)
androidTestImplementation(libs.androidx.test.runner)
androidTestImplementation(libs.androidx.test.ext.junit)
androidTestImplementation(libs.truth)
```

`testInstrumentationRunner` is already `androidx.test.runner.AndroidJUnitRunner`.

- [ ] **Step 2: Write `EcgPackagedBundleSmokeTest`**

Instrumented test that:

1. Loads `EcgAnalysisBundle.load(context)` from packaged assets (not a sidecar copy).
2. Asserts `compatibilityId == "ecg-nao3-student-256hz-v1"` and both artifact hashes match the manifest (runtime `load` already checks hashes).
3. Builds a 30 s 500 Hz clean QRS `ParsedEcgFile` (schema v2, `hr_bpm` empty).
4. Runs `EcgRhythmEngine(context).analyze(parsed)`.
5. Asserts `status == OK` **or** (if the emulator cannot create an Interpreter) `status == FAILED` with `ecgHrMedian` within 8 of 72, quality present, bundle id current, `decision == null`. Prefer `OK` with finite `pNormal`/`pAf`/`pOther` when the Interpreter runs.
6. Asserts sample count 15_000 is not required for the synthetic fixture; if using a 30 s 500 Hz generator, `n = 15_000`.

Use `@RunWith(AndroidJUnit4::class)` and `ApplicationProvider.getApplicationContext()`.

- [ ] **Step 3: Run build gates**

```powershell
git ls-files --eol -- app/src/main/assets/ecg/
.\gradlew :app:verifyEcgNao3Bundle :app:testDebugUnitTest :app:assembleDebug
```

Expected: JSON `w/lf`; all tasks succeed.

If a device/emulator is connected, also run:

```powershell
.\gradlew :app:connectedDebugAndroidTest
```

If none is connected, record that in the report; do not fail the task solely for missing hardware. The test class must still compile via `:app:assembleDebug` / compileAndroidTest.

- [ ] **Step 4: Commit**

```powershell
git add gradle/libs.versions.toml app/build.gradle.kts app/src/androidTest
git commit -m "test: packaged NAO3 bundle device smoke and AndroidX Test 1.7.0"
```

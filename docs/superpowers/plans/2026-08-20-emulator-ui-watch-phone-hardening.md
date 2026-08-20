# Emulator UI and Watch–Phone Sync Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Exercise the phone and Wear OS apps on available emulator/device targets, verify the ECG transfer contract end to end, fix confirmed UI or synchronization defects, and leave repeatable regression coverage.

**Architecture:** The phone module receives `/ecg/session/{sessionId}` DataItems through `EcgWearListenerService`, persists parsed sessions in Room, and exposes them through Compose screens. The wear module records either Samsung ECG data or the deterministic demo trace, persists files in `WatchEcgStore`, and sends gzip CSV assets through `WatchDataLayer`. Investigation will separate environment failures (missing SDK/emulator, unavailable Samsung tracker, missing model) from product defects before changing code.

**Tech Stack:** Kotlin, Android Gradle Plugin, Jetpack Compose/Material 3, Wear Compose, Google Play Services Wearable Data Layer, Room, WorkManager, JUnit/Truth, adb, Android phone and Wear OS emulators.

**Spec:** `PROTOCOL.md` and the user request to test the UI and watch–phone connection.

## Global Constraints

- Keep phone and watch `applicationId` values identical (`app.healthtrack`) and preserve the existing Data Layer paths and payload keys in `PROTOCOL.md`.
- Treat ECG output as personal tracking only; do not describe it as a medical diagnosis.
- Preserve unacknowledged watch recordings and the existing cleanup-marker semantics.
- Do not change vendor Samsung Health permissions or security settings during emulator testing.
- Use the deterministic “Record demo” path when Samsung ECG hardware/partner access is unavailable.

### Task 1: Establish a reproducible baseline

**Files:**
- Read: `README.md`, `PROTOCOL.md`, `app/build.gradle.kts`, `wear/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `wear/src/main/AndroidManifest.xml`
- Test: `protocol/src/test`, `app/src/test`, `wear/src/test`

**Interfaces:**
- Consumes: configured Android SDK, Gradle wrapper, installed AVDs/devices, existing unit tests.
- Produces: a baseline build/test result, available emulator identifiers, and a short list of environment limitations.

- [ ] **Step 1: Resolve Android tooling and targets**

  Run the SDK-local tools instead of relying on `PATH`:

  ```powershell
  & 'C:\Users\foxka\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices -l
  & 'C:\Users\foxka\AppData\Local\Android\Sdk\emulator\emulator.exe' -list-avds
  ```

  Record only returned device/AVD names; do not invent a target.

- [ ] **Step 2: Run the existing unit-test baseline**

  ```powershell
  .\gradlew.bat :protocol:test :app:testDebugUnitTest :wear:testDebugUnitTest
  ```

  Expected: the command either passes or reports a concrete tool/dependency failure that is kept separate from application defects.

- [ ] **Step 3: Build debug APKs when the baseline allows it**

  ```powershell
  .\gradlew.bat :app:assembleDebug :wear:assembleDebug
  ```

  Expected: `app/build/outputs/apk/debug/app-debug.apk` and `wear/build/outputs/apk/debug/wear-debug.apk` exist.

### Task 2: Exercise phone UI and lifecycle

**Files:**
- Read/verify: `app/src/main/java/app/healthtrack/MainActivity.kt`, `app/src/main/java/app/healthtrack/ui/HealthTrackNav.kt`, `app/src/main/java/app/healthtrack/ui/HealthTrackViewModel.kt`
- Read/verify: `app/src/main/java/app/healthtrack/ui/home/HomeScreen.kt`, `app/src/main/java/app/healthtrack/ui/history/HistoryScreen.kt`, `app/src/main/java/app/healthtrack/ui/detail/EcgDetailScreen.kt`, `app/src/main/java/app/healthtrack/ui/settings/SettingsScreen.kt`
- Modify: only the confirmed defect files.
- Test: the nearest existing unit test, plus a focused regression test under `app/src/test` when state/formatting behavior is changed.

**Interfaces:**
- Consumes: installed debug phone APK and the phone emulator window returned by the Windows automation API.
- Produces: observations for cold start, navigation, empty state, settings, back navigation, rotation/recreation if available, and an imported/synced session.

- [ ] **Step 1: Install and launch the phone APK**

  ```powershell
  & 'C:\Users\foxka\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk
  & 'C:\Users\foxka\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s emulator-5554 shell am force-stop app.healthtrack
  & 'C:\Users\foxka\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s emulator-5554 shell monkey -p app.healthtrack 1
  ```

- [ ] **Step 2: Verify the primary phone flows**

  Check that Home renders without clipping or an indefinite loading state; open History and Settings; use system back to return to Home; confirm empty states are readable; and capture logcat if the activity crashes.

- [ ] **Step 3: Exercise the detail flow with a known session**

  Use the watch sync flow from Task 3 or a protocol-valid import, open the newest session, and verify waveform, duration, heart-rate text, analysis state, and back navigation. Treat a missing ONNX model as an environment limitation unless the UI fails to communicate it safely.

### Task 3: Exercise Wear UI and the phone connection

**Files:**
- Read/verify: `wear/src/main/java/app/healthtrack/wear/MainWearActivity.kt`, `wear/src/main/java/app/healthtrack/wear/ui/WearNav.kt`, `wear/src/main/java/app/healthtrack/wear/ui/WearViewModels.kt`
- Read/verify: `wear/src/main/java/app/healthtrack/wear/ui/HomeScreen.kt`, `wear/src/main/java/app/healthtrack/wear/ui/MeasureScreen.kt`, `wear/src/main/java/app/healthtrack/wear/ui/HistoryScreen.kt`, `wear/src/main/java/app/healthtrack/wear/ui/SettingsScreen.kt`
- Read/verify: `wear/src/main/java/app/healthtrack/wear/sync/WatchDataLayer.kt`, `wear/src/main/java/app/healthtrack/wear/sync/WatchWearListenerService.kt`, `wear/src/main/java/app/healthtrack/wear/store/WatchEcgStore.kt`, `wear/src/main/java/app/healthtrack/wear/capture/EcgSessionRecorder.kt`
- Modify: only the confirmed defect files.
- Test: focused tests under `wear/src/test` and/or `protocol/src/test` for the defect contract.

**Interfaces:**
- Consumes: installed debug watch APK, a paired Wear OS target when available, and the phone app from Task 2.
- Produces: evidence for phone-link status, Samsung fallback, demo recording, local save, DataItem delivery, duplicate delivery, retry/reconnect, and cleanup acknowledgement.

- [ ] **Step 1: Install and launch the watch APK**

  ```powershell
  & 'C:\Users\foxka\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s emulator-5556 install -r wear\build\outputs\apk\debug\wear-debug.apk
  & 'C:\Users\foxka\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s emulator-5556 shell am force-stop app.healthtrack
  & 'C:\Users\foxka\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s emulator-5556 shell am start -n app.healthtrack/.wear.MainWearActivity
  ```

- [ ] **Step 2: Verify watch UI states**

  Check Home, Settings wrist selection, History empty state, Measure’s Samsung unavailable state, “Record demo”, countdown/recording, cancel, saving, success, and retry wording. Confirm buttons remain reachable on the round watch viewport.

- [ ] **Step 3: Verify the end-to-end demo transfer**

  Start “Record demo”, wait for save/send completion, confirm the watch reports either “On your phone” or a safe saved-on-watch fallback, then check the phone History and detail screens. Use logcat from both package processes to distinguish Data Layer failure from parsing/persistence failure.

- [ ] **Step 4: Verify delivery semantics**

  Repeat sync after a reconnect and after reopening the phone app. Confirm the same `sessionId` is idempotent on the phone, the watch retains unacknowledged files, and cleanup creates the `.synced` marker rather than deleting immediately. If no paired target exists, execute the equivalent pure Kotlin contract tests and report the hardware/emulator limitation.

### Task 4: Fix confirmed defects with regression coverage

**Files:**
- Modify: the smallest set of phone/watch/protocol files implicated by observed failures.
- Test: the closest existing test file, or a new focused test in `app/src/test`, `wear/src/test`, or `protocol/src/test`.

**Interfaces:**
- Consumes: concrete failing flow and logs from Tasks 2–3.
- Produces: a minimal fix that preserves `PROTOCOL.md` behavior and a test that fails before the fix and passes after it.

- [ ] **Step 1: Encode the observed failure as a focused test**

  Assert the exact state transition, path/key, persistence behavior, or UI-facing error that failed; avoid broad snapshot tests that depend on emulator timing.

- [ ] **Step 2: Implement the smallest compatible fix**

  Keep existing module boundaries and coroutine/lifecycle ownership. Do not remove safety fallbacks or acknowledge a file before the phone has ingested it.

- [ ] **Step 3: Run the focused test and the affected module tests**

  ```powershell
  .\gradlew.bat :protocol:test :app:testDebugUnitTest :wear:testDebugUnitTest
  ```

  Expected: the regression and all existing tests pass.

### Task 5: Rebuild, reinstall, and rerun the discovered flows

**Files:**
- Modify: none unless the rerun reveals another confirmed defect.
- Verify: generated debug APKs and installed package versions.

**Interfaces:**
- Consumes: the fixed source tree and the same emulator/device identifiers used in Tasks 2–3.
- Produces: final build/test status, rerun evidence, and a concise list of remaining environment limitations.

- [ ] **Step 1: Build both debug APKs**

  ```powershell
  .\gradlew.bat :app:assembleDebug :wear:assembleDebug
  ```

- [ ] **Step 2: Reinstall and rerun phone and watch smoke flows**

  Recheck cold start, primary navigation, demo record, sync, History/detail, reconnect, and no-crash behavior.

- [ ] **Step 3: Report results**

  Report each fixed defect with file references, the regression test, the verification command, and any limitation such as absent AVD, unavailable Samsung tracker, missing ECGFounder asset, or unavailable paired Data Layer.

# GalaxyVitals Package Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the project package identity `app.healthtrack` with `app.galaxyvitals` across the phone app, Wear app, shared protocol module, tests, and active documentation.

**Architecture:** The phone and Wear application modules continue to share one Android application ID so the Wear Data Layer can pair them, but the shared identity becomes `app.galaxyvitals`. Kotlin namespaces become `app.galaxyvitals` for the phone/shared code and `app.galaxyvitals.wear` for Wear-specific code. Existing Data Layer paths, payload keys, database filename, and Samsung health API stub packages remain unchanged because they are independent contracts.

**Tech Stack:** Gradle Kotlin DSL, Android application modules, Kotlin/JVM, Jetpack Compose, Wear OS, shared Kotlin protocol module.

**Spec:** User request in the current task: change the remaining `app.healthtrack` package to the GalaxyVitals package because this is a personal-use project.

## Global Constraints

- The new phone and Wear `applicationId` must be exactly `app.galaxyvitals`.
- The phone/shared Kotlin package root must be `app.galaxyvitals`; the Wear-specific root must be `app.galaxyvitals.wear`.
- Keep `/ecg/session/{id}`, `/rpc/req`, `/ecg/cleanup`, payload keys, and the `healthtrack.db` filename unchanged.
- Do not rewrite historical plan documents; update active README/help text and current source references only.
- Run protocol tests, phone/Wear unit tests, debug APK assembly, and a final stale-package search.

---

### Task 1: Update Android package metadata and active documentation

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `wear/build.gradle.kts`
- Modify: `README.md`
- Modify: `wear/libs/README.md`

**Interfaces:**
- Consumes: Existing shared phone/Wear application ID and namespace configuration.
- Produces: `namespace = "app.galaxyvitals"`, `namespace = "app.galaxyvitals.wear"`, and matching `applicationId = "app.galaxyvitals"` values.

- [ ] **Step 1: Update Gradle namespace and application IDs**

Replace only these identifiers:

```kotlin
// app/build.gradle.kts
namespace = "app.galaxyvitals"
applicationId = "app.galaxyvitals"

// wear/build.gradle.kts
namespace = "app.galaxyvitals.wear"
applicationId = "app.galaxyvitals"
```

- [ ] **Step 2: Update active package references in documentation**

Change the compatibility and Samsung package text in `README.md` and `wear/libs/README.md` from `app.healthtrack` to `app.galaxyvitals`, without changing protocol paths or the database filename.

- [ ] **Step 3: Verify metadata before source migration**

Run:

```powershell
rg -n 'app\.healthtrack|app\.galaxyvitals|applicationId|namespace' app/build.gradle.kts wear/build.gradle.kts README.md wear/libs/README.md
```

Expected: both application IDs are `app.galaxyvitals`, the Wear namespace ends in `.wear`, and no old package reference remains in these active files.

### Task 2: Move source/test directories and migrate Kotlin package declarations

**Files:**
- Move: `app/src/main/java/app/healthtrack` to `app/src/main/java/app/galaxyvitals`
- Move: `app/src/test/java/app/healthtrack` to `app/src/test/java/app/galaxyvitals`
- Move: `wear/src/main/java/app/healthtrack` to `wear/src/main/java/app/galaxyvitals`
- Move: `wear/src/test/java/app/healthtrack` to `wear/src/test/java/app/galaxyvitals`
- Move: `protocol/src/main/java/app/healthtrack` to `protocol/src/main/java/app/galaxyvitals`
- Move: `protocol/src/test/java/app/healthtrack` to `protocol/src/test/java/app/galaxyvitals`
- Modify: all Kotlin files under those moved trees containing `app.healthtrack`

**Interfaces:**
- Consumes: New Gradle namespaces from Task 1.
- Produces: Compilable source and test packages rooted at `app.galaxyvitals`.

- [ ] **Step 1: Move the six exact package directory trees**

Use filesystem moves for the six source/test roots above; do not delete or recreate individual files.

- [ ] **Step 2: Replace package declarations and imports**

Apply the mechanical replacement below only to Kotlin sources/tests in `app/src`, `wear/src`, and `protocol/src`:

```text
app.healthtrack -> app.galaxyvitals
```

This changes shared protocol packages as well as the Wear-specific `app.galaxyvitals.wear` descendants automatically.

- [ ] **Step 3: Update the internal foreground-service action**

Change the private action constant in `wear/src/main/java/app/galaxyvitals/wear/capture/MeasureForegroundService.kt` from:

```kotlin
const val ACTION_STOP = "app.healthtrack.wear.STOP_MEASURE"
```

to:

```kotlin
const val ACTION_STOP = "app.galaxyvitals.wear.STOP_MEASURE"
```

Keep all Wear Data Layer paths unchanged.

### Task 3: Verify compilation, tests, and package identity

**Files:**
- Test: `protocol/src/test`
- Test: `app/src/test`
- Test: `wear/src/test`
- Verify: generated debug manifests/APKs and active source tree

**Interfaces:**
- Consumes: Migrated source trees and Gradle metadata from Tasks 1–2.
- Produces: Debug APKs whose phone/Wear package identity is `app.galaxyvitals` and a clean source migration.

- [ ] **Step 1: Search for stale package references**

Run:

```powershell
rg -n -i 'app\.healthtrack' --glob '!**/build/**' --glob '!docs/superpowers/plans/**' --glob '!.gradle/**' .
```

Expected: no output.

- [ ] **Step 2: Run the full verification build**

Run:

```powershell
.\gradlew.bat :protocol:test :app:testDebugUnitTest :wear:testDebugUnitTest :app:assembleDebug :wear:assembleDebug
```

Expected: `BUILD SUCCESSFUL` and fresh APKs at `app/build/outputs/apk/debug/app-debug.apk` and `wear/build/outputs/apk/debug/wear-debug.apk`.

- [ ] **Step 3: Validate package identity in generated artifacts**

Use Android SDK `apkanalyzer` or `aapt2 dump badging` on both APKs and verify the package is `app.galaxyvitals`; also verify that `README.md` still documents the unchanged Data Layer paths and `healthtrack.db` filename.

- [ ] **Step 4: Check the final diff**

Run:

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors; changes are limited to the package migration and the already-present GalaxyVitals branding work.

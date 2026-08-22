# GalaxyVitals Branding and Launcher Asset Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the current GalaxyBridge branding to GalaxyVitals and make the muted `branding/galaxyvitals-logo.png` the source for the phone and watch launcher icons without changing the existing phone/watch pairing identity.

**Architecture:** Keep the stable `app.healthtrack` namespaces, application IDs, signing assumptions, Data Layer paths, and `healthtrack.db` filename unchanged. Update only the visible brand, non-contract app class/theme names, documentation, and the launcher rendering pipeline; generate both phone and Wear OS launcher resources from the project-local GalaxyVitals logo.

**Tech Stack:** Kotlin, Android Gradle Plugin, Compose, Wear OS resources, Python/Pillow launcher asset script, PNG branding asset.

**Spec:** The user's request in this task, plus the existing compatibility contract in `README.md` and `PROTOCOL.md`.

## Global Constraints

- The visible product name is exactly `GalaxyVitals`.
- Phone and watch `applicationId` values remain exactly `app.healthtrack`.
- Kotlin package namespaces remain under `app.healthtrack`.
- Existing Wear Data Layer paths, payload keys, signing assumptions, and `healthtrack.db` remain unchanged.
- The launcher source asset is `branding/galaxyvitals-logo.png` and uses the muted palette aligned with `#071016`.
- Historical implementation plans under `docs/superpowers/plans/` are not rewritten.

---

### Task 1: Update visible GalaxyVitals branding

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `wear/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values/themes.xml`
- Modify: `wear/src/main/res/values/themes.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `wear/src/main/AndroidManifest.xml`
- Modify: user-facing Kotlin strings in `app/src/main/java/app/healthtrack/` and `wear/src/main/java/app/healthtrack/wear/`
- Modify: `README.md`, `PROTOCOL.md`, `tools/ecgfounder/COMPARE_RESULTS.md`, and `tools/render_launcher_icon.py`

**Interfaces:**
- Consumes: Existing labels, themes, and compatibility documentation.
- Produces: `GalaxyVitals` as the visible app/project name while preserving `app.healthtrack` and all protocol identifiers.

- [ ] **Step 1: Replace visible `GalaxyBridge` text with `GalaxyVitals`**

  Update the root project name, launcher string resources, user-facing connection/help text, current documentation, and the launcher script docstring. Do not replace occurrences inside historical plans or stable package/application IDs.

- [ ] **Step 2: Rename the non-contract theme resources**

  Change `Theme.GalaxyBridge` to `Theme.GalaxyVitals` in both manifests and both `themes.xml` files. Keep the theme parents and colors unchanged.

- [ ] **Step 3: Verify the brand boundary**

  Run:

  ```powershell
  rg -n -i 'GalaxyBridge' README.md PROTOCOL.md settings.gradle.kts app/src wear/src tools
  rg -n 'applicationId = "app\.healthtrack"|namespace = "app\.healthtrack' app/build.gradle.kts wear/build.gradle.kts
  ```

  Expected: no active-source `GalaxyBridge` branding remains; both modules still use `app.healthtrack`.

### Task 2: Rename the application entry point without changing package identity

**Files:**
- Move: `app/src/main/java/app/healthtrack/GalaxyBridgeApp.kt` to `app/src/main/java/app/healthtrack/GalaxyVitalsApp.kt`
- Modify: `app/src/main/java/app/healthtrack/GalaxyVitalsApp.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/app/healthtrack/data/wear/EcgWearListenerService.kt`
- Modify: `app/src/main/java/app/healthtrack/ui/HealthTrackViewModel.kt`

**Interfaces:**
- Consumes: The existing `Application` implementation and stable `app.healthtrack` package.
- Produces: `app.healthtrack.GalaxyVitalsApp`, referenced by the phone manifest and casts, with unchanged initialization behavior.

- [ ] **Step 1: Move and rename the application class**

  Keep the class body byte-for-byte equivalent except for the class name:

  ```kotlin
  class GalaxyVitalsApp : Application() {
  ```

- [ ] **Step 2: Update manifest and casts/imports**

  Point `android:name` and all imports/casts at `.GalaxyVitalsApp` / `GalaxyVitalsApp`. Keep the package declaration `package app.healthtrack`.

- [ ] **Step 3: Compile-check the entry point**

  Run:

  ```powershell
  .\gradlew.bat :app:compileDebugKotlin
  ```

  Expected: BUILD SUCCESSFUL with no unresolved `GalaxyBridgeApp` references.

### Task 3: Make `branding/galaxyvitals-logo.png` the launcher source

**Files:**
- Modify: `branding/galaxyvitals-logo.png` by replacing it with the muted GalaxyVitals logo.
- Modify: `tools/render_launcher_icon.py`
- Modify: generated phone launcher files under `app/src/main/res/`
- Modify: generated watch launcher files under `wear/src/main/res/`

**Interfaces:**
- Consumes: Transparent `branding/galaxyvitals-logo.png` and the existing Android adaptive-icon resource structure.
- Produces: Phone and watch launcher foreground/full-resolution resources derived from the same muted source image.

- [ ] **Step 1: Promote the muted logo to the canonical branding path**

  Copy `branding/galaxyvitals-logo-muted.png` over `branding/galaxyvitals-logo.png`; retain the muted sibling for comparison until verification is complete.

- [ ] **Step 2: Update the renderer to load the canonical branding asset**

  Change `tools/render_launcher_icon.py` to open `ROOT / "branding" / "galaxyvitals-logo.png"`, preserve its alpha channel, fit it inside the adaptive-icon safe area, composite opaque launcher variants over `INK = (7, 16, 22, 255)`, and write the existing phone resources. Mirror the generated resources into the Wear module so both launchers use the same mark.

- [ ] **Step 3: Regenerate and inspect launcher resources**

  Run:

  ```powershell
  py tools/render_launcher_icon.py
  ```

  Confirm the generated foreground and launcher PNGs exist in both modules and remain readable on the `#071016` background.

### Task 4: Full verification

**Files:**
- Test: `protocol`, `app`, and `wear` Gradle test suites.
- Inspect: active source branding and generated resources.

**Interfaces:**
- Consumes: Completed GalaxyVitals source/resources.
- Produces: A buildable phone/watch project with the stable `app.healthtrack` pairing identity and updated labels/icons.

- [ ] **Step 1: Run the relevant tests and builds**

  ```powershell
  .\gradlew.bat :protocol:test :app:testDebugUnitTest :wear:testDebugUnitTest :app:assembleDebug :wear:assembleDebug
  ```

  Expected: all requested tasks pass.

- [ ] **Step 2: Verify the final identity contract**

  ```powershell
  rg -n -i 'GalaxyBridge' README.md PROTOCOL.md settings.gradle.kts app/src wear/src tools
  rg -n 'GalaxyVitals|app\.healthtrack|healthtrack\.db' README.md PROTOCOL.md settings.gradle.kts app/src wear/src app/build.gradle.kts wear/build.gradle.kts
  git status --short
  ```

  Expected: active branding is `GalaxyVitals`; `app.healthtrack` and `healthtrack.db` remain intact; the canonical logo and generated launcher assets are present.

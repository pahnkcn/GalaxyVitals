# GalaxyBridge Branding and Phone/Watch Build Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the phone and Wear OS launcher experience use the requested `GalaxyBridge` name, preserve phone/watch compatibility, and produce fresh installable debug APKs for both devices.

**Architecture:** The project contains separate `:app` and `:wear` Android application modules that intentionally share the `app.healthtrack` application ID for Wear Data Layer delivery. Only user-facing branding and the internal application class/theme resource names will change; the application ID and Kotlin package namespaces remain stable so an existing paired installation can update without losing its communication contract.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, Android Gradle Plugin, Jetpack Compose, Wear OS, Android resources, JUnit, and debug APK packaging.

**Spec:** The current user request, with the existing phone/watch install contract documented in `README.md` and `PROTOCOL.md`.

## Global Constraints

- Preserve all unrelated pre-existing working-tree changes; do not reset, clean, or overwrite them.
- The phone and watch `applicationId` values remain exactly `app.healthtrack` and continue using the same debug signing identity.
- The visible launcher label for both the phone app and watch app is exactly `GalaxyBridge`.
- Build both `:app:assembleDebug` and `:wear:assembleDebug`; the deliverables are the resulting `app-debug.apk` and `wear-debug.apk` files.
- Validate with the existing unit tests before handing off the APKs.
- Use the repository's Android SDK configuration from `local.properties` and the checked-in Gradle wrapper.

---

### Task 1: Baseline the existing branding and build outputs

**Files:**
- Read: `app/src/main/AndroidManifest.xml`
- Read: `app/src/main/res/values/strings.xml`
- Read: `app/src/main/res/values/themes.xml`
- Read: `wear/src/main/AndroidManifest.xml`
- Read: `wear/src/main/res/values/strings.xml`
- Read: `wear/src/main/res/values/themes.xml`
- Read: `app/build.gradle.kts`
- Read: `wear/build.gradle.kts`
- Read: `README.md`

**Interfaces:**
- Consumes: The current manifests, resource names, package/application IDs, and Gradle tasks.
- Produces: A verified list of branding edits and the two Gradle build targets used by later tasks.

- [x] **Step 1: Record the current launcher labels and identity contract**

Run:

```powershell
rg --no-ignore -n -i 'HealthTrack|GalaxyBridge|applicationId|namespace|android:label|android:theme|android:name="\.HealthTrackApp"' app wear README.md
```

Expected: the phone label is already `GalaxyBridge`, the watch label/theme still contain `HealthTrack`, and both application IDs are `app.healthtrack`.

- [x] **Step 2: Confirm the current build tasks are available**

Run:

```powershell
.\gradlew.bat :app:tasks --all
.\gradlew.bat :wear:tasks --all
```

Expected: both modules expose `assembleDebug`, `testDebugUnitTest`, and manifest/resource processing tasks.

- [x] **Step 3: Preserve the dirty-worktree boundary**

Run:

```powershell
git status --short -- app wear README.md docs
```

Expected: existing modifications are recorded mentally and no reset/checkout/clean command is used.

### Task 2: Apply GalaxyBridge branding without changing the pairing identity

**Files:**
- Create: `app/src/main/java/app/healthtrack/GalaxyBridgeApp.kt`
- Delete: `app/src/main/java/app/healthtrack/HealthTrackApp.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/themes.xml`
- Modify: `app/src/main/java/app/healthtrack/data/wear/EcgWearListenerService.kt`
- Modify: `app/src/main/java/app/healthtrack/data/wear/WearSyncClient.kt`
- Modify: `app/src/main/java/app/healthtrack/ui/HealthTrackViewModel.kt`
- Modify: `app/src/main/java/app/healthtrack/ui/home/HomeScreen.kt`
- Modify: `app/src/main/java/app/healthtrack/ui/settings/SettingsScreen.kt`
- Modify: `wear/src/main/AndroidManifest.xml`
- Modify: `wear/src/main/res/values/strings.xml`
- Modify: `wear/src/main/res/values/themes.xml`
- Modify: `wear/src/main/java/app/healthtrack/wear/sync/WatchDataLayer.kt`
- Modify: `wear/src/main/java/app/healthtrack/wear/ui/HomeScreen.kt`
- Modify: `wear/src/main/java/app/healthtrack/wear/ui/WearViewModels.kt`
- Modify: `README.md`

**Interfaces:**
- Consumes: The stable `app.healthtrack` namespace/application IDs and the existing `HealthTrackApp`/theme references.
- Produces: `GalaxyBridgeApp` as the phone `Application` entry point, `Theme.GalaxyBridge` and `Theme.GalaxyBridge.Wear` resources, and `GalaxyBridge` as both launcher labels while leaving `app.healthtrack` unchanged.

- [x] **Step 1: Write the new phone application class with unchanged behavior**

Create `app/src/main/java/app/healthtrack/GalaxyBridgeApp.kt` with the existing implementation and only the class name changed:

```kotlin
package app.healthtrack

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GalaxyBridgeApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch {
            container.ecgRepository.ingestPendingInbox()
        }
    }
}
```

- [x] **Step 2: Point manifests and themes at the GalaxyBridge names**

In `app/src/main/AndroidManifest.xml`, change the application class to `.GalaxyBridgeApp` and both `Theme.HealthTrack` references to `Theme.GalaxyBridge`. In `wear/src/main/AndroidManifest.xml`, change the application theme from `Theme.HealthTrack.Wear` to `Theme.GalaxyBridge.Wear`.

In `app/src/main/res/values/themes.xml`, rename the style from `Theme.HealthTrack` to `Theme.GalaxyBridge`. In `wear/src/main/res/values/themes.xml`, rename the style from `Theme.HealthTrack.Wear` to `Theme.GalaxyBridge.Wear` without changing parent styles or colors.

- [x] **Step 3: Set the watch launcher label to the requested name**

Change the `app_name` resource in `wear/src/main/res/values/strings.xml` from `HealthTrack` to `GalaxyBridge`; retain the measurement strings unchanged. Confirm `app/src/main/res/values/strings.xml` remains `GalaxyBridge`.

- [x] **Step 4: Update user-facing documentation while explicitly preserving the package ID**

In `README.md`, change the watch-app description from `HealthTrack` to `GalaxyBridge`, and add the compatibility explanation that the phone/watch `applicationId app.healthtrack` is intentionally retained so the existing Wear Data Layer contract and paired installs continue to match.

Update the remaining user-facing connection/help text in `EcgWearListenerService.kt`, `WearSyncClient.kt`, `HealthTrackViewModel.kt`, both `HomeScreen.kt` files, `SettingsScreen.kt`, `WatchDataLayer.kt`, and `WearViewModels.kt` from `HealthTrack` to `GalaxyBridge`. Update imports and casts from `HealthTrackApp` to `GalaxyBridgeApp`; leave internal Kotlin type names, the stable `app.healthtrack` ID, and the existing `healthtrack.db` filename unchanged.

- [x] **Step 5: Run a source-level branding audit**

Run:

```powershell
rg --no-ignore -n -i 'HealthTrack|Theme\.HealthTrack|health track' app/src wear/src README.md
rg --no-ignore -n 'applicationId = "app\.healthtrack"|namespace = "app\.healthtrack' app/build.gradle.kts wear/build.gradle.kts
```

Expected: no old user-facing `HealthTrack` label, help text, or theme remains; internal Kotlin identifiers and the stable package/application ID lines may still contain `HealthTrack`/`healthtrack`.

### Task 3: Test, package, and verify the phone/watch APK deliverables

**Files:**
- Read: `app/build/outputs/apk/debug/app-debug.apk`
- Read: `wear/build/outputs/apk/debug/wear-debug.apk`
- Read: `app/build/outputs/logs/manifest-merger-debug-report.txt`
- Read: `wear/build/outputs/logs/manifest-merger-debug-report.txt`

**Interfaces:**
- Consumes: The GalaxyBridge-branded source/resources from Task 2.
- Produces: Fresh debug APKs at `app/build/outputs/apk/debug/app-debug.apk` and `wear/build/outputs/apk/debug/wear-debug.apk`, both using `app.healthtrack` and both carrying the `GalaxyBridge` label.

- [x] **Step 1: Run all existing JVM tests**

Run:

```powershell
.\gradlew.bat :protocol:test :app:testDebugUnitTest :wear:testDebugUnitTest
```

Expected: all protocol, phone, and watch unit tests pass.

- [x] **Step 2: Build fresh debug APKs for both targets**

Run:

```powershell
.\gradlew.bat :app:assembleDebug :wear:assembleDebug
```

Expected: Gradle completes successfully and regenerates both APK paths listed above.

- [x] **Step 3: Verify APK metadata and output sizes**

Run the Android SDK build-tools `apkanalyzer` (or `aapt2 dump badging` if `apkanalyzer` is unavailable) against both APKs and verify the package is `app.healthtrack`, the application label is `GalaxyBridge`, and the watch APK declares the watch feature. Also run:

```powershell
Get-Item app\build\outputs\apk\debug\app-debug.apk, wear\build\outputs\apk\debug\wear-debug.apk |
    Select-Object FullName,Length,LastWriteTime
```

Expected: both files exist, have non-zero sizes, and have timestamps from the current build.

- [x] **Step 4: Re-run the branding audit against merged manifests**

Run:

```powershell
rg -n -i 'HealthTrack|GalaxyBridge|app\.healthtrack' app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml wear/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml
```

Expected: merged manifests resolve the label to `GalaxyBridge`, use the renamed theme/application class, and keep the shared `app.healthtrack` identity.

- [x] **Step 5: Report the two install commands and compatibility note**

Hand off the absolute APK paths plus:

```powershell
adb -d install -r app\build\outputs\apk\debug\app-debug.apk
adb -e install -r wear\build\outputs\apk\debug\wear-debug.apk
```

Explain that `-d` targets the phone and `-e` targets the paired Wear OS emulator/device as in the repository README, and that a real Samsung ECG sensor may still require the existing privileged SDK/partner access.

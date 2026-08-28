# GalaxyVitals

Open-source Android companion for Galaxy Watch ECG recordings.

GalaxyVitals implements the wearable **Data Layer + gzip CSV** contract used to
move ECG traces from a watch to a phone. The UI and runtime integration are
original. This repository is named GalaxyBridge; the visible app and
`applicationId` are GalaxyVitals (`app.galaxyvitals`).

**Not a medical device.** Readings are for personal tracking only. If you feel
unwell, seek professional care.

## What works today

- Import `ecg_*.csv.gz` / `.csv` files that match [PROTOCOL.md](PROTOCOL.md)
- Wear Data Layer listener on `/ecg/session/{id}` (same app id + signing key)
- `:wear` GalaxyVitals watch app (`applicationId app.galaxyvitals`) that records and pushes that contract
- Waveform viewer, Samsung processed HR/IBI stats, source-aware history, and an
  explicitly labelled ECG-derived BPM fallback
- On-device **N / A / O** rhythm screen after each import or watch sync (**N**ormal / **A**F / **O**ther)
- Architecture stub so blood pressure can be added later

## Requirements

- JDK 17
- Android SDK 36 (`local.properties` → `sdk.dir`)
- Phone minSdk 32 (Android 12L+), watch minSdk 33
- Version `0.1.0` (`versionCode` 1) on both apps

On Windows use `gradlew.bat` in place of `./gradlew`.

| Module | Role |
|---|---|
| `:app` | Phone companion |
| `:wear` | Watch capture and sync |
| `:protocol` | gzip CSV / Data Layer contract |
| `:samsung-health-api` | Compile stub when the official AAR is absent |

## Live watch sync

Install both debug APKs on a paired phone and Wear OS watch. They share
`app.galaxyvitals` and the same debug signing key, so `/ecg/session/{id}` is
delivered to the phone. The package ID is intentionally retained for the
existing phone/watch Data Layer pairing even though the visible app name is
GalaxyVitals.

```bash
./gradlew :app:assembleDebug :wear:assembleDebug
adb -d install -r app/build/outputs/apk/debug/app-debug.apk
adb -e install -r wear/build/outputs/apk/debug/wear-debug.apk   # watch after pairing
```

On the watch: **Start ECG**. Hardware ECG needs the official Privileged Health SDK AAR at
`wear/libs/samsung-health-sensor-api.aar` (see [wear/libs/README.md](wear/libs/README.md))
*and* a Samsung partner whitelist for `app.galaxyvitals`.

During a measurement the watch runs `HEART_RATE_CONTINUOUS` beside the single
`ECG_ON_DEMAND` capture. Samsung HR with status `1` is primary; ECG-derived BPM
is used only if processed HR is invalid or older than 3 seconds. On API 36 this
requires Samsung `READ_ADDITIONAL_HEALTH_DATA` and Android
`READ_HEART_RATE`; API 35 and earlier use `BODY_SENSORS`.

A differently signed vendor watch app still cannot talk to this phone app.

## Build

```bash
./gradlew :protocol:test :app:testDebugUnitTest :wear:testDebugUnitTest \
  :app:assembleDebug :wear:assembleDebug
```

Prebuilt sideload APKs and checksums for 0.1.0 are described in
[dist/RELEASE_NOTES.md](dist/RELEASE_NOTES.md). The `.apk` files themselves are
gitignored.

## Rhythm model

Labels are **N**ormal, **A**F, and **O**ther.

The phone runs the direct three-class NAO3 model locally through LiteRT. The
hash-bound FP32 reference is the packaged default. The host-parity FP16 and
rejected INT8 candidates remain under `models/nao3/` and are not packaged as
runtime fallbacks until the quantized row passes target-device verification.
See [models/nao3/README.md](models/nao3/README.md) for hashes, host parity, and
why FP32 is the packaged default.

Stored watch traces remain raw at 500 Hz. For rhythm analysis, the phone applies
the recording polarity once, resamples to 256 Hz, applies the configured filter
chain, z-scores the whole record, and center-fits it to 7,680 samples. The model
returns logits in N / A / O order; the app applies stable softmax and reports the
argmax model score only after the signal-quality gate.

The detail chart uses a separate display-only 0.5–40 Hz filter and visible-range
scaling. Display processing never overwrites the stored or exported ECG.

Host invocation and FP16 numerical parity are verified. Android uses the FP32
reference until quantized numerical behavior is validated on an ADB-connected
phone; target-phone performance and delegate behavior remain unclaimed.

**Not a medical device.** Treat AF flags as a prompt to see a clinician.

## License

Apache License 2.0. See [LICENSE](LICENSE).

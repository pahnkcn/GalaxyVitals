# GalaxyVitals 0.1.0

First GitHub release of **GalaxyVitals** — an open-source Galaxy Watch ECG companion. This tag ships **both** APKs: phone app and watch app.

**Not a medical device.** Readings are for personal tracking only. If you feel unwell, seek professional care.

## Downloads

Install **both** APKs from this release. They share package id `app.galaxyvitals` and the same signing certificate, which is required for Wear Data Layer sync (`/ecg/session/{id}`).

| File | Device | minSdk |
|---|---|---|
| `GalaxyVitals-0.1.0-phone.apk` | Android phone | 32 (Android 12L+) |
| `GalaxyVitals-0.1.0-watch.apk` | Wear OS / Galaxy Watch | 33 |

## Phone app

- Receive ECG sessions from a paired GalaxyVitals watch
- Import `ecg_*.csv.gz` / `.csv` files that match [PROTOCOL.md](https://github.com/pahnkcn/GalaxyVitals/blob/main/PROTOCOL.md)
- Waveform viewer, heart-rate stats, and history
- On-device **N / A / O** rhythm screen after import or watch sync
- Blood pressure screen is a placeholder for later work

## Watch app

- 30-second ECG capture at 500 Hz
- Live BPM from the PPG green sensor
- Short sensor-stabilize window before recording starts
- Screen stays on during measurement
- History list and phone sync
- Home / measure / settings flow with tap-target guidance

## Install

```bash
adb -d install -r GalaxyVitals-0.1.0-phone.apk
adb -e install -r GalaxyVitals-0.1.0-watch.apk
```

Pair the watch with the phone through Wear OS / Galaxy Wearable, then open GalaxyVitals on both devices.

Hardware ECG on a Galaxy Watch also needs Samsung’s Privileged Health SDK and a partner whitelist for `app.galaxyvitals`. Without that whitelist, `ECG_ON_DEMAND` will not be granted on a stock watch.

## SHA-256

```
8a72f81e41cb2a64f8444969c7492d7bf68521b8c935022b6976494c050bc94d  GalaxyVitals-0.1.0-phone.apk
dd4427b2433060646dd1d73bbf0dc5589cc08ddb916b6717fd4e99a5400a3f86  GalaxyVitals-0.1.0-watch.apk
```

## Notes for this first release

- Version `0.1.0` (`versionCode` 1) on both APKs
- Sideload builds signed with the Android **debug** key so phone and watch can talk to each other. A later Play Store / dedicated release key would require uninstalling both apps first.
- Apache License 2.0

# GalaxyBridge

Open-source Android companion for Galaxy Watch ECG recordings.

GalaxyBridge implements the wearable **Data Layer + gzip CSV** contract used to
move ECG traces from a watch to a phone. The UI is original. It does not
redistribute another vendor’s layouts, icons, or classification models.

**Not a medical device.** Readings are for personal tracking only. If you feel
unwell, seek professional care.

## What works today

- Import `ecg_*.csv.gz` / `.csv` files that match [PROTOCOL.md](PROTOCOL.md)
- Wear Data Layer listener on `/ecg/session/{id}` (same app id + signing key)
- Waveform viewer, HR stats, history
- Architecture stub so blood pressure can be added later

## What cannot work

Google Wear Data Layer only syncs between apps that share **package name and
signing certificate**. GalaxyBridge uses `app.healthtrack`. It cannot receive
live sessions from a differently signed watch app (including GeminiMan Wellness
Companion). Use **Import recording** for those files.

Samsung on-device ECG capture itself requires the privileged Health Tracking
partner SDK. This repo does not modify or ship a watch APK.

## Build

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Requires Android SDK 35. `local.properties` should set `sdk.dir`.

## License

Apache License 2.0. See [LICENSE](LICENSE).

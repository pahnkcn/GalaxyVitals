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
- `:wear` GalaxyBridge watch app (`applicationId app.healthtrack`) that records and pushes that contract
- Waveform viewer, HR stats, history
- On-device **ECGFounder 1-lead** rhythm screen (N / A / O) after each import or watch sync
- Architecture stub so blood pressure can be added later

## Live watch sync

Install both debug APKs on a paired phone and Wear OS watch. They share
`app.healthtrack` and the same debug signing key, so `/ecg/session/{id}` is
delivered to the phone. The package ID is intentionally retained for the
existing phone/watch Data Layer pairing even though the visible app name is
GalaxyBridge.

```bash
./gradlew :app:assembleDebug :wear:assembleDebug
adb -d install -r app/build/outputs/apk/debug/app-debug.apk
adb -e install -r wear/build/outputs/apk/debug/wear-debug.apk   # watch after pairing
```

On the watch: **Start ECG**. If Samsung Health Tracking does not grant
`ECG_ON_DEMAND` to this package, use **Record demo** to verify the Data Layer
path. Hardware ECG needs the official Privileged Health SDK AAR at
`wear/libs/samsung-health-sensor-api.aar` *and* a Samsung partner whitelist for
`app.healthtrack`.

A differently signed vendor watch app still cannot talk to this phone app.

## Build

```bash
./gradlew :protocol:test :app:testDebugUnitTest :wear:testDebugUnitTest \
  :app:assembleDebug :wear:assembleDebug
```

Requires Android SDK 36. `local.properties` should set `sdk.dir`.

## Rhythm model

The phone app runs [ECGFounder 1-lead](https://github.com/PKUDigitalHealth/ECGFounder) (MIT)
locally through ONNX Runtime. Convert the Hugging Face checkpoint once:

```bash
py -3.12 tools/ecgfounder/export_ecgfounder.py --pth 1_lead_ECGFounder.pth
```

The ONNX asset is intentionally not committed; release builds fail fast until
the verified exporter has provisioned it.

Watch traces stay at 500 Hz. The analyser cuts 10 s windows, applies the configured
band-pass / notch / z-score pipeline, then maps the 150 outputs to N / A / O.

**Not a medical device.** Treat AF flags as a prompt to see a clinician.

## License

Apache License 2.0. See [LICENSE](LICENSE).

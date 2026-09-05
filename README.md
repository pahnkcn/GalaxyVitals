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
- Waveform viewer, source-aware HR/BPM history, and an explicitly labelled
  handoff from pre-measurement Samsung HR to ECG-derived BPM
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

Before a measurement the watch runs `HEART_RATE_CONTINUOUS` alone until three
successful readings over at least 1.5 seconds agree within 5 BPM. It then closes
that tracker before opening the bounded ECG contact/stabilization probe. After
1.5 seconds of usable ECG and a 3-second quality-preserving countdown, the probe
is restarted as the 30-second `ECG_ON_DEMAND` capture. The pre-measurement HR is
held on screen with a clear label until reliable ECG-derived BPM is available;
the continuous HR tracker never overlaps an on-demand ECG tracker. On API 36
this requires Samsung `READ_ADDITIONAL_HEALTH_DATA` and Android
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

The phone runs a three-class rhythm model locally through LiteRT. It is a
single-lead 1D CNN (~2.0 M parameters) trained for this app: pretrained on lead I
of Chapman-Shaoxing/Ningbo and PTB-XL, then fine-tuned on PhysioNet/CinC
Challenge 2017, whose handheld single-lead recordings are the closest public
analogue to a wrist capture. Preprocessing during training is a line-by-line port
of what the app does at inference, so the model is never shown a window the
device cannot produce.

Packaged asset: `ecg_nao3_student_fp32.tflite`, 8,026,200 bytes, sha256
`c98a8356837673980d3622d45156e78bb898bca587bc456091544e1d6461fdba`, bound by
`ecg_nao3_bundle.json` and verified at build time. FP32 is packaged rather than
the FP16 export -- which matches it on host to 7.7e-03 with full argmax agreement
-- because no quantized row has passed target-device verification.

Stored watch traces remain raw at 500 Hz. For rhythm analysis, the phone applies
the recording polarity once, resamples to 256 Hz, applies the configured filter
chain, z-scores the whole record, and center-fits it to 7,680 samples. The model
returns logits in N / A / O order; the app applies stable softmax and reports the
argmax model score only after the signal-quality gate.

A class is only decidable once it has demonstrated at least 0.90 precision on a
sealed, record-disjoint CinC 2017 evaluation split, opened once. In the packaged
model (`ecg-nao3-student-256hz-v4`) **N and AF both clear that bar** -- 0.9269
and 0.9118 over 1,781 records -- and O abstains by design. Earlier builds shipped
with AF abstaining, at 0.8438 and 0.8767.

Precision is not sensitivity. AF is flagged on roughly 4 % of recordings and
catches about 41 % of true AF, so a quiet result is not evidence of a normal
rhythm. The 95 % lower bound on that AF precision is 0.82 on 68 predictions: the
point estimate clears the bar, the interval does not. Target-device behaviour and
delegate selection remain unclaimed; only host parity is verified.

## Signal chain

`ECG_ON_DEMAND` gives raw, unfiltered samples: a large electrode offset that
polarizes over the first second, and mains interference that on a wrist capture
is comparable to the R wave. `EcgSignalChain` measures the real sample rate from
the stored Samsung timestamps (501.67 Hz on Galaxy Watch, not the declared 500),
finds the powerline frequency in the recording instead of assuming 50 or 60 Hz,
removes it with zero-phase notches, and takes out baseline with a 200/600 ms
median cascade that absorbs the polarization step rather than ringing on it.

Display uses monitor bandwidth (40 Hz); anything reported as a number uses
diagnostic bandwidth (150 Hz), because a 40 Hz cutoff costs 15-20% of R-wave
amplitude. See [PROTOCOL.md](PROTOCOL.md) for the measured before/after numbers.
Display processing never overwrites the stored or exported ECG.

Host invocation and FP16 numerical parity are verified. Android uses the FP32
reference until quantized numerical behavior is validated on an ADB-connected
phone; target-phone performance and delegate behavior remain unclaimed.

**Not a medical device.** Treat AF flags as a prompt to see a clinician.

## License

Apache License 2.0. See [LICENSE](LICENSE).

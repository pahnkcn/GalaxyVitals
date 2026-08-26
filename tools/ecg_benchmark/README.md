# ECG BPM benchmark (engineering validation)

Opt-in PhysioNet harness for the shared `EcgBeatAnalyzer`. This is **not** clinical
validation and does not claim diagnostic performance.

NeuroKit2 `0.2.13` is a **reference-only** Python pipeline. It is not a Gradle or
production dependency. On MIT-BIH, WFDB `.atr` beat annotations (N/V/etc) are the
detector ground truth; do not use `ecg_process(correct_artifacts=True)` as BPM GT.

Prepared files and any raw downloads live under `_analysis/ecg_benchmark/` (gitignored).
Do not commit PhysioNet records, converted CSVs, or `_analysis/` outputs.

## Prepare

```powershell
python -m pip install -r tools/ecg_benchmark/requirements.txt
python tools/ecg_benchmark/prepare_physionet.py --limit 1
python tools/ecg_benchmark/prepare_physionet.py
```

`--limit N` converts a smoke subset (MIT-BIH non-paced first, then NSTDB). Paced
MIT-BIH records `102`, `104`, `107`, and `217` are skipped. A full run (no
`--limit`) **fails** if any of the 44 non-paced MIT-BIH records or 12 NSTDB
records are missing, including `118e00`, `118e_6`, `119e00`, and `119e_6`
(WFDB format 16). The script downloads only when you run it;
`.\gradlew.bat :protocol:test` does not download PhysioNet.

Record/participant split: [physionet_split.csv](physionet_split.csv) (dev vs
locked). Detector thresholds are chosen on **dev** only and frozen in
`EcgBeatDetectorConfig`. Opt-in Kotlin gates run on the **locked** set.

Output per record:

- `signal.f32` — little-endian float32 mV resampled to 500 Hz (watch native rate)
- `beats.csv` — WFDB `.atr` beat times in milliseconds
- `meta.txt` — `sign_factor`, channel, SNR (NSTDB), sample count
- `neurokit_rpeaks.csv` — optional NeuroKit2 0.2.13 reference peaks (`correct_artifacts=False`)
- `manifest.csv` — index consumed by the Kotlin test

## Opt-in test

Default unit tests skip the harness. Enable it only after `prepare_physionet.py` has
written `_analysis/ecg_benchmark/manifest.csv`:

```powershell
$env:GALAXYVITALS_PHYSIONET_BENCHMARK = "1"
.\gradlew.bat :protocol:test --tests app.galaxyvitals.data.protocol.PhysioNetBpmBenchmarkTest
```

Gates (10 s `EcgBeatAnalyzer.analyzeWindow` slices, 150 ms peak match):

- MIT-BIH non-paced: R-peak sensitivity/PPV ≥ 99%; median HR MAE ≤ 2 BPM on accepted windows
- NSTDB SNR ≥ 12 dB: coverage ≥ 80%; accepted HR MAE ≤ 5 BPM
- High-noise NSTDB: abstain allowed; among reported windows, fraction with error > 10 BPM ≤ 5%

Gates are locked-split engineering checks, not clinical accuracy claims. Do not
retune `EcgBeatDetectorConfig` from locked-set output. Do not change live BPM
estimator thresholds to chase these numbers.

## Status (2026-08-26) — locked gates unmet

Human decision: **option 1**. Keep the opt-in assertions strict. Do not tune
from locked 119. Do not resplit after seeing the holdout.

`EcgBeatDetectorConfig` v3 was frozen on **dev** only. Default
`:protocol:test` still skips this harness. When
`GALAXYVITALS_PHYSIONET_BENCHMARK=1` and the full 44+12 corpus is prepared,
the **locked** tests are expected to fail:

| Gate | Need | Dev (freeze set) | Locked (holdout) |
|---|---|---|---|
| MIT se / PPV | ≥99% / ≥99% | 99.15% / 99.09% | 98.26% / 98.59% |
| MIT median HR MAE | ≤2 BPM | pass | pass |
| NSTDB ≥12 dB coverage | ≥80% | 87.6% | 51.9% |
| High-noise error >10 BPM | ≤5% | 4.62% | 9.27% |

Locked NSTDB is entirely participant **119**. That is an engineering miss, not
a license to claim the detector passed. Algorithm acceptance remains **NO-GO**.
Watch9 production also remains NO-GO until SM-L350 hardware validation and
Samsung package/signing registration. These numbers are not Watch9 wrist-lead
performance and are not clinical accuracy.

## Hardware

Watch acceptance on SM-L350 is a human checklist: [HARDWARE_ACCEPTANCE.md](HARDWARE_ACCEPTANCE.md).
It cannot be executed in this repository task.

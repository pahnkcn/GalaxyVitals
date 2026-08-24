# SM-L350 hardware acceptance (human checklist)

Engineering validation of live BPM / waveform stability on Galaxy Watch SM-L350
(480×480). **Not clinical validation.** Do not claim diagnostic performance.
This checklist cannot be marked passed from CI or this repository task.

Use **new** sessions only. Do not read or replay old GalaxyVitals ECG sessions.
Raw traces from these rounds may be stored only under `_analysis/` (gitignored);
do not upload or commit them. PPG is not persisted in production.

Do **not** open a continuous Samsung Health tracker together with ECG on-demand
during this test ([Samsung data specifications](https://developer.samsung.com/health/sensor/guide/data-specifications.html)).

## Sessions (5 only)

1. Resting / still — 3 times
2. Changing finger pressure — 1 time
3. Wrist movement — 1 time

## Resting rounds

- Count the pulse for a full 30 seconds and multiply by two.
- Compare that manual rate with the watch PPG reading.
- If the two references differ by more than 5 BPM, the round is **inconclusive** — repeat it.
- Final displayed BPM must be within ±5 BPM of both the manual pulse and PPG.
- After a 10 second warm-up, live BPM must stay within ±5 BPM of the reference for at least 90% of the time the system chooses to show a value.

## Pressure / motion

- When pressure change or wrist motion corrupts the signal, a wrong or stale BPM must hide within 3 seconds.

## Waveform

- The graph must not invert on the right wrist.
- QRS complexes must not disappear because of downsampling.
- Scale must not jump because of a single outlier.

## Sign-off

| Check | Pass | Notes |
|---|---|---|
| Resting 1 final BPM ±5 vs manual and PPG | | |
| Resting 2 final BPM ±5 vs manual and PPG | | |
| Resting 3 final BPM ±5 vs manual and PPG | | |
| Live BPM ±5 for ≥90% of displayed time after 10 s warm-up | | |
| Pressure: hide bad/stale BPM within 3 s | | |
| Motion: hide bad/stale BPM within 3 s | | |
| Right-wrist graph not inverted | | |
| QRS survives downsampling | | |
| Single outlier does not collapse scale | | |
| No continuous Samsung tracker during on-demand ECG | | |

Operator: ________  Date: ________  Watch firmware: ________

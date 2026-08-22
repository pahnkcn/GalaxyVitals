# nao_full vs ECGFounder 1-lead

Balanced sample from **PhysioNet/CinC 2017** public AliveCor records:
200 Normal / 200 AF / 200 Other. Single-lead handheld ECG, 9–60 s, 300 Hz.

This is the closest open set to a Galaxy Watch strip. It is **not** a Samsung
watch corpus. Official 2017 hidden-test labels are private.

| Condition | Model | Accuracy | Macro-F1 | AF AUROC | AF F1 |
|---|---|---:|---:|---:|---:|
| Clean 2017 | **nao_full fp32** | **0.868** | **0.865** | **0.982** | **0.926** |
| Clean 2017 | ECGFounder INT8 | 0.620 | 0.522 | 0.974 | 0.885 |
| Watch-like 500 Hz | **nao_full fp32** | **0.827** | **0.822** | **0.969** | **0.884** |
| Watch-like 500 Hz | ECGFounder INT8 | 0.608 | 0.509 | 0.966 | 0.860 |

Watch-like = same strip resampled to 500 Hz, then baseline wander, 50 Hz hum,
EMG, dropouts, and random polarity. Both models read that same degraded file.

## Per-class (watch-like, the Galaxy Watch proxy)

| Class | nao P / R / F1 | Founder P / R / F1 |
|---|---|---|
| N | 0.76 / 0.97 / 0.85 | 0.48 / 0.99 / 0.65 |
| A | 0.89 / 0.88 / 0.88 | 0.89 / 0.83 / 0.86 |
| O | 0.85 / 0.64 / 0.73 | 0.40 / 0.01 / 0.02 |

## How to read this

- **nao is better at the 3-class N/A/O job** this app shows the user.
- **AF screening is close.** AUROC 0.97 vs 0.97 on the watch-like set.
- Founder almost never says Other: it calls most non-AF strips Normal
  (`SINUS RHYTHM` fires on many CinC “O” records). That mapping is ours,
  from 150 hospital labels down to N/A/O.
- Clean 2017 may flatter nao. The I/O of `nao_full` *is* the 2017 task,
  so GeminiMan may have trained on these public files. ECGFounder treated
  2017 as an external set. The watch-like column is the fairer one for
  GalaxyVitals.

## After training a frozen N/A/O head

The 90M ECGFounder backbone stayed frozen. A logistic layer was trained on
480 other 2017 records (plus watch-style copies) and tested on the **same
held-out 600** as above.

| Condition | Mapping | Accuracy | Macro-F1 | AF AUROC | O recall |
|---|---|---:|---:|---:|---:|
| Clean | old rules | 0.620 | 0.522 | 0.974 | 0.015 |
| Clean | **calibrated head** | **0.805** | **0.804** | 0.960 | **0.705** |
| Watch-like | old rules | 0.608 | 0.509 | 0.966 | 0.010 |
| Watch-like | **calibrated head** | **0.792** | **0.789** | **0.966** | **0.650** |
| Watch-like | nao_full (reference) | 0.827 | 0.822 | 0.969 | 0.640 |

Most of the 3-class gap is gone. AF detection stays in the same band as nao.
The phone app now loads `assets/ecg/nao_calibrator.json`.

Reproduce the head:

```bash
py -3.12 tools/ecgfounder/compare_nao_founder.py --per-class 200
py -3.12 tools/ecgfounder/train_nao_head.py --train-per-class 160
```

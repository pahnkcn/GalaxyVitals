# ECG retrieval protocol

GalaxyVitals implements the wearable ECG file contract used by Galaxy Watch
companions that push a gzip CSV over the Play Services Wearable Data Layer.

This document describes the **on-the-wire format** only. GalaxyVitals does not
include vendor UI, assets, or classification models.

## Capture (watch module `:wear`)

The GalaxyVitals watch app records:

- `HealthTrackerType.ECG_ON_DEMAND` → `ValueKey.EcgSet.ECG_MV` (millivolts)
- Before ECG, `HealthTrackerType.HEART_RATE_CONTINUOUS` runs by itself. HR is
  usable only when `HEART_RATE_STATUS == 1`; an IBI is usable only when its
  paired `IBI_STATUS_LIST` value is `0` and the IBI is nonzero. Preflight accepts
  three distinct successful readings spanning at least 1.5 seconds when their
  spread is at most 5 BPM, with a 15-second timeout.
- The continuous HR tracker is closed before any on-demand ECG listener starts.
  A bounded ECG probe then requires 750 consecutive usable samples (1.5 seconds
  at 500 Hz): valid contact, ordered timestamps/continuous batch sequence,
  finite values, no acquisition flags, and values inside Samsung's reported
  saturation thresholds. These conditions must remain true throughout the
  following 3-second countdown.
- The probe listener is closed and restarted as the actual 30-second capture.
  Its raw samples and timer begin at zero; only the causal display filter state
  is retained to avoid a graph startup transient. Probe samples are never stored
  as part of the recording.
- During capture, the UI holds the accepted value under **Heart rate before ECG**
  until reliable ECG/embedded-PPG BPM is available. The held value is never
  represented as a concurrent or current Samsung reading. Persisted/final live
  BPM statistics use the app estimator and remain non-gating for ECG capture.

This is a bounded stabilization/quality gate, not a hardware calibration. It
does not infer unpublished Samsung gain/ADC constants, rescale `ECG_MV`, or alter
the raw values written to the recording.
- Lead-off, sequence, and saturation thresholds from the first point in each batch;
  every `LEAD_OFF != 0` is invalid contact
- Exactly 30 s of sensor time (15,000 samples at nominal 500 Hz),
  `sessionId = currentTimeMillis`
- Wrist `LEFT` → `signFactor +1`, `RIGHT` → `-1`
- Raw polarity and every sensor timestamp are preserved; `signFactor` is applied
  only to derived display/preprocessing data
- New hardware captures write schema v3. v1 and v2 files remain readable.

Samples are written as gzip CSV under `filesDir/ecg/ecg_{sessionId}.csv.gz`.

Samsung ECG is a privileged tracker. Hardware capture requires both the official
client AAR and a partner-whitelisted package.

Wear target API 36 requests Samsung
`com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA`
for raw ECG and `android.permission.health.READ_HEART_RATE` for processed HR.
Devices through API 35 use `BODY_SENSORS` instead. Measurement is foreground-only;
the continuous HR listener is closed before ECG and on cancel, error, host stop,
or shutdown. Runtime guards reject any attempt to overlap continuous HR and ECG.

GalaxyVitals v1 implements session receive, `syncNow`, and `cleanup`. A cleanup
message creates the exact acknowledgement marker
`ecg_{sessionId}.csv.gz.synced`; it does not immediately delete the recording.
`syncNow` uploads only gzip files without this marker. When trimming local
history, the watch may remove acknowledged recordings older than the newest
eight entries, but it never prunes an unacknowledged recording.

The phone retains ECG-derived BPM and morphology analysis as an independent
fallback/research path and may run the on-device NAO3 student. Rhythm output is fail-closed: without a calibrated
decision-policy artifact the UI must not show N / A / O (`INDETERMINATE`).
That step is analysis, not part of the on-the-wire contract. Algorithm
acceptance on PhysioNet locked gates is unmet; production remains NO-GO
until Watch9 hardware validation and Samsung registration.

## Signal chain (`EcgSignalChain`)

`ECG_ON_DEMAND` delivers **raw, unfiltered** samples. Everything that draws or
measures a recording runs through one chain so display, beat detection and
morphology see the same signal. Stored raw rows are never modified, `ECG_MV` is
never rescaled, and no undocumented Samsung constant is inferred.

| Stage | What it does |
|---|---|
| Clock | Regresses `sensor_timestamp_ms_raw` against `sample_index` (outliers trimmed at 4 MAD). Falls back to nominal when there are fewer than 32 distinct stamps or the fit lands >5% off nominal. |
| Powerline | Estimates the interference frequency from the recording (Goertzel sweep 39-71 Hz, accepted only within 1.5 Hz of 50 or 60 Hz and 6x above a two-sided local floor), then removes the fundamental and harmonics with zero-phase RBJ notches at `Q = 20`. |
| Baseline | 200 ms then 600 ms running median, replicate-padded. The first stage removes QRS so the second tracks wander only, which keeps ST and T. Being nonlinear it cannot ring, so the electrode-polarization step is absorbed rather than amplified. |
| Low-pass | Zero-phase Butterworth, order 4, at the selected bandwidth. |

Two bandwidths, per the AHA/ACC/HRS standardization recommendations:

- `DIAGNOSTIC` (150 Hz) for anything reported as a number.
- `MONITOR` (40 Hz) for the on-screen trace only. A 40 Hz cutoff costs 15-20% of
  R-wave amplitude on these captures, so measurements are never taken from it.

Measured on Galaxy Watch (`SM-L350`, sensor SDK 1.4.1) 30 s captures:

| | Declared / previous | Measured |
|---|---|---|
| Sample rate | 500 Hz | **501.67 Hz** (two independent estimates agree: timestamp regression, and the mains line landing on 50.01 Hz only at that rate) |
| Mains fundamental | not removed anywhere | 0.52 mVpp raw, suppressed 30 dB |
| Start-of-record artifact | 8.3 mV over a 0.62 mV R wave, ~2.5 s unusable | 0.74 mV against a 0.70 mV steady-state peak, `settleSampleIndex = 0` |
| Baseline estimator | single 0.4 s median (12-13% T attenuation) | 200/600 ms cascade |

The 0.33% clock error made every RR interval, and therefore every reported BPM,
0.33% low. `ParsedEcgFile.effectiveSrHz` is recomputed from the raw timestamp
column for schema v3; the `effective_sr_hz` metadata field is derived from the
reconstructed `sample_index x 2` grid and only ever restates the nominal rate.

Known limits: the 600 ms baseline stage is longer than RR above roughly 150 bpm,
where it starts to track the beat instead of the baseline; a `Q = 20` notch
removes ECG energy in a 2.5 Hz band around the line frequency.

The watch's live preview applies the same notch causally. The line frequency is
estimated once from the first 3 s of the pre-capture probe and the configured
notch is carried into the recording window, so the live trace never restarts
unfiltered.

## File format (`format = csv+gz`)

UTF-8 text, then gzip.

```
#meta={"schema_version":3,"format":"csv_mv_v3",...}
rel_ms,sample_index,ecg_raw_mv,flags,hr_bpm,sensor_timestamp_ms_raw,batch_sequence,batch_sample_offset,batch_size
0,0,-0.12,0,,1000,0,0,5
2,1,-0.11,0,,1000,0,1,5
#bpm={"id":0,"at_sample_index":0,"observed_capture_elapsed_ms":0,"status":"COLLECTING",...}
```

### Schema v3 `#meta` JSON

| Field | Type | Meaning |
|---|---|---|
| `schema_version` | number | Required value `3`. |
| `sr_hz` | number | Nominal native rate; Samsung ECG is `500`. |
| `effective_sr_hz` | number | Rate from sample count and the reconstructed analysis clock. |
| `unit` | string | Required physical amplitude unit `mV`. |
| `ts_start` | number | Wall-clock epoch millis for display/audit only. |
| `sensor_start_ms` | number | First stored `sensor_timestamp_ms_raw` value. |
| `timing_trust` | string | Analysis-clock trust: `SEQUENCE_RECONSTRUCTED`. |
| `analysis_clock_source` | string | `SAMPLE_INDEX_2MS` (`rel_ms = sample_index × 2`). |
| `raw_clock_source` | string | `SAMSUNG_DATAPOINT_MS`. |
| `raw_timing_trust` | string | Trust of unmodified raw timestamps (`UNVERIFIED` until independently confirmed). |
| `raw_sensor_duration_ms` | number | `last raw timestamp − first raw timestamp`; not the analysis duration. |
| `listener_duration_ms` | number | Time the on-demand listener was open. |
| `format` | string | `csv_mv_v3`. |
| `capture_source` | string | `HARDWARE` or `IMPORT`. |
| `sample_count` / `duration_ms` | number | Declared row count and reconstructed analysis span (`(n-1)×2` ms). |
| `gap_count` / `repeated_timestamp_count` / `batch_count` | number | Raw-timestamp / batch diagnostics. They do not rewrite timestamps. |
| `missing_sample_count` | number | Reserved; sequence gaps cannot count missing samples exactly. |
| `missing_sample_count_known` | boolean | Always `false` for v3. |
| `sequence_gap_count` | number | Samsung batch sequence discontinuities. |
| `contact_loss_count` / `clipped_sample_count` | number | Contact and saturation summary. |
| `acquisition_flags` | number | Bitmask of acquisition errors. |
| `min_threshold_mv` / `max_threshold_mv` | number/null | Samsung saturation limits. |
| `sensor_sdk` / `sensor_aar_sha256` | string/null | SDK version and AAR SHA-256 provenance. |
| `live_bpm_*` | mixed | Summary of `#bpm` lines (RELIABLE-only median/min/max/coverage). New hardware capture uses `live_bpm_algorithm_id=app.galaxyvitals.live_bpm.v1`; the older Samsung-primary ID remains readable for existing files. |
| `watch_info` | string | JSON blob (device, firmware, sensor SDK, app version). |
| `wrist` | string | `LEFT` or `RIGHT`. |
| `signFactor` | number | Derived polarity transform (`±1`), not applied to raw rows. |
| `polarityNormalized` | boolean | Always `false` for v3 capture. |

`encodeParsed()` writes the file's real schema version. A v3 file is never
downgraded to v2.

### Schema v3 rows

| Column | Meaning |
|---|---|
| `rel_ms` | Reconstructed analysis time: `sample_index × 2` ms. |
| `sample_index` | Contiguous zero-based index; duplicates/gaps are rejected. |
| `ecg_raw_mv` | Unmodified ECG amplitude in millivolts. |
| `flags` | Per-sample acquisition bitmask. |
| `hr_bpm` | Reserved optional legacy HR side-channel; hardware capture leaves it empty. |
| `sensor_timestamp_ms_raw` | Unmodified Samsung DataPoint timestamp. |
| `batch_sequence` | Samsung batch `SEQUENCE` (0–255; `255→0` is a valid wrap). |
| `batch_sample_offset` | Index of this sample inside its delivery batch. |
| `batch_size` | Size of that delivery batch (the original callback size, including a trimmed tail). |

`#bpm` lines follow the rows. At most 64 observations; `id` values are
consecutive from 0; `observed_capture_elapsed_ms` never goes backwards.
Every observation may include these Samsung provenance fields:

| Field | Meaning |
|---|---|
| `sensor_timestamp_ms` | Unmodified timestamp of the Samsung HR `DataPoint`. |
| `sensor_status` | Raw `HEART_RATE_STATUS`; only `1` can be `RELIABLE`. |
| `ibi_ms` | Raw `IBI_LIST`, in delivery order (at most four values). |
| `ibi_status` | Raw status paired one-to-one with `ibi_ms`; `0` plus a nonzero IBI is usable. |

For sources `SAMSUNG_HEART_RATE_PREFLIGHT` and the historical
`SAMSUNG_HEART_RATE_CONTINUOUS`, `RELIABLE` requires a positive displayed BPM,
source, sensor timestamp, and `sensor_status == 1`; app bSQI is intentionally
absent. New captures emit the former only, at capture elapsed time zero, with
reason `PRE_MEASUREMENT_HEART_RATE`. App-derived sources require bSQI and RR
count. Live BPM summary uses only `RELIABLE` observations, duration-weighted,
and cuts a value when its estimate age exceeds 3 s. The UI may continue showing
the clearly labelled pre-measurement value after that TTL, but it is not counted
as current/reliable summary coverage. The legacy per-sample `hr_bpm` column
remains empty for hardware capture.

### Schema v2 `#meta` JSON

Schema v2 remains readable. v2 files do **not** store per-sample raw
timestamps. Historical writers labeled `timing_trust=SENSOR` and
`clock_source=SAMSUNG_DATAPOINT_MS`, but `rel_ms` was `sample_index × 2` ms
without the raw timestamp column. Parsers therefore treat the **effective**
timing trust of v2 as `UNVERIFIED` even when the metadata string says
`SENSOR`. Stored v2 gzip bytes are not rewritten.

| Field | Type | Meaning |
|---|---|---|
| `schema_version` | number | Required value `2`. |
| `sr_hz` | number | Nominal native rate; Samsung ECG is `500`. |
| `effective_sr_hz` | number | Rate computed from sample count and the stored `rel_ms` span. |
| `unit` | string | Required physical amplitude unit `mV`. |
| `ts_start` | number | Wall-clock epoch millis for display/audit only. |
| `sensor_start_ms` | number | First sensor timestamp recorded at capture time (not per-row). |
| `timing_trust` | string | Metadata may say `SENSOR`; effective parsed trust is `UNVERIFIED`. |
| `format` | string | `csv_mv_v2`. |
| `capture_source` | string | `HARDWARE` or `IMPORT`; `LEGACY` is used only while reading pre-v2 files. |
| `sample_count` / `duration_ms` | number | Declared row count and stored `rel_ms` span. |
| `gap_count` / `missing_sample_count` | number | Timestamp continuity summary. |
| `sequence_gap_count` | number | Samsung batch sequence discontinuities. |
| `contact_loss_count` / `clipped_sample_count` | number | Contact and saturation summary. |
| `acquisition_flags` | number | Bitmask of acquisition errors. |
| `min_threshold_mv` / `max_threshold_mv` | number/null | Samsung saturation limits. |
| `watch_info` | string | JSON blob (device, firmware, sensor SDK, app version). |
| `wrist` | string | `LEFT` or `RIGHT`. |
| `signFactor` | number | Derived polarity transform (`±1`), not applied to raw rows. |
| `polarityNormalized` | boolean | Always `false` for v2 capture. |

Schema v1 (`rel_ms,value_mv,hr_bpm`) remains readable. Its index-generated clock
is marked `timingTrust=ASSUMED`; v1 files keep their prior result and are shown as
legacy/unverified. Writers never discard waveform rows because HR is missing.

### Schema v2 rows

| Column | Meaning |
|---|---|
| `rel_ms` | `sample_index × 2` ms on the reconstructed grid; not a stored raw sensor timestamp. |
| `sample_index` | Contiguous zero-based index; duplicates/gaps are rejected. |
| `ecg_raw_mv` | Unmodified ECG amplitude in millivolts. |
| `flags` | Per-sample acquisition bitmask. |
| `hr_bpm` | Reserved optional side channel; hardware v2 capture leaves it empty. |

## Wear Data Layer

Play Services Wearable only delivers items between apps that share **application
id and signing certificate**. A different package cannot receive another vendor’s
DataItems.

### Session push (watch → phone)

`DataClient.putDataItem` (urgent, timeout `0`):

| Key | Type | Value |
|---|---|---|
| path | — | `/ecg/session/{sessionId}` |
| `sessionId` | String | id without `ecg_` / `.csv.gz` |
| `ts` | Long | `currentTimeMillis` |
| `format` | String | `csv+gz` |
| `nonce` | Long | change token so the item updates |
| `byteCount` | Long | exact gzip payload length |
| `sha256` | String | lowercase SHA-256 of the gzip payload |
| `ecgFile` | Asset | gzip bytes |

### Phone receive

`WearableListenerService` + `DATA_CHANGED` filter `wear://*/ecg/session`:

1. Take path after `/ecg/session/`.
2. `DataClient.getFdForAsset(ecgFile)` (30s).
3. Verify byte count and SHA-256 before parsing or acknowledging.
4. Preserve schema-v2 gzip bytes immutably, persist metadata, and show the recording.
5. A repeated session id with different content is quarantined and not acknowledged.

### Optional messages

| Path | Direction | Purpose |
|---|---|---|
| `/rpc/req/{nodeId}/syncNow` | phone → watch | ask watch to re-push unacknowledged inbox files |
| `/ecg/cleanup/{sessionId}` | phone → watch | mark that exact watch copy acknowledged after ingest |
| `/ecg/ack/{sessionId}` | watch → phone | ack |
| `/ecg/result/{sessionId}` | phone → watch | JSON summary |
| `/ecg/delete/{sessionId}` | either | delete one |
| `/ecg/delete_all` | either | wipe |
| `/ecg/restore/` / `/ecg/restore-batch/` | phone → watch | restore |

The phone implements session receive, `syncNow`, and `cleanup`. The `:wear` app
implements session push, `syncNow`, and `cleanup`.

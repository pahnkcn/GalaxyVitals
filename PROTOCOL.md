# ECG retrieval protocol

GalaxyVitals implements the wearable ECG file contract used by Galaxy Watch
companions that push a gzip CSV over the Play Services Wearable Data Layer.

This document describes the **on-the-wire format** only. GalaxyVitals does not
include vendor UI, assets, or classification models.

## Capture (watch module `:wear`)

The GalaxyVitals watch app records:

- `HealthTrackerType.ECG_ON_DEMAND` → `ValueKey.EcgSet.ECG_MV` (millivolts)
- No concurrent continuous tracker; BPM is derived later from ECG R-peaks
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

GalaxyVitals v1 implements session receive, `syncNow`, and `cleanup`. A cleanup
message creates the exact acknowledgement marker
`ecg_{sessionId}.csv.gz.synced`; it does not immediately delete the recording.
`syncNow` uploads only gzip files without this marker. When trimming local
history, the watch may remove acknowledged recordings older than the newest
eight entries, but it never prunes an unacknowledged recording.

The phone then runs ECGFounder 1-lead locally on 10 s windows at 500 Hz and
stores an N / A / O screen with the top model findings. That step is analysis,
not part of the on-the-wire contract.

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
| `live_bpm_*` | mixed | Summary of `#bpm` lines (RELIABLE-only median/min/max/coverage). |
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
`RELIABLE` requires displayed BPM, source, bSQI, and RR count. Live BPM
summary uses only `RELIABLE` observations, duration-weighted, and cuts a
value when its estimate age exceeds 3 s.

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

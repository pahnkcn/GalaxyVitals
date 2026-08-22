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
#meta={"schema_version":2,...}
rel_ms,sample_index,ecg_raw_mv,flags,hr_bpm
0,0,-0.12,0,
2,1,-0.11,0,
```

### Schema v2 `#meta` JSON

| Field | Type | Meaning |
|---|---|---|
| `schema_version` | number | Required value `2`. |
| `sr_hz` | number | Nominal native rate; Samsung ECG is `500`. |
| `effective_sr_hz` | number | Rate computed from sample count and sensor duration. |
| `unit` | string | Required physical amplitude unit `mV`. |
| `ts_start` | number | Wall-clock epoch millis for display/audit only. |
| `sensor_start_ms` | number | Sensor timestamp of the first sample in Samsung's documented millisecond unit. |
| `timing_trust` | string | `SENSOR`; legacy v1 is parsed as `ASSUMED`. |
| `format` | string | `csv_mv_v2`. |
| `capture_source` | string | `HARDWARE` or `IMPORT`; `LEGACY` is used only while reading pre-v2 files. |
| `sample_count` / `duration_ms` | number | Declared row count and sensor-time span. |
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

### Rows

| Column | Meaning |
|---|---|
| `rel_ms` | Milliseconds from the first sensor timestamp. |
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

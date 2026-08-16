# ECG retrieval protocol

GalaxyBridge implements the wearable ECG file contract used by Galaxy Watch
companions that push a gzip CSV over the Play Services Wearable Data Layer.

This document describes the **on-the-wire format** only. GalaxyBridge does not
include vendor UI, assets, or classification models.

## Capture (watch module `:wear`)

The HealthTrack watch app records:

- `HealthTrackerType.ECG_ON_DEMAND` → `ValueKey.EcgSet.ECG_MV` (millivolts)
- `HealthTrackerType.HEART_RATE_CONTINUOUS` → bpm, aligned by timestamp
- Lead-off via `ValueKey.EcgSet.LEAD_OFF` (`5` = no contact)
- 30s session, `sessionId = currentTimeMillis`
- Wrist `LEFT` → `signFactor +1`, `RIGHT` → `-1`
- Sample clock `rel_ms = i * 1000 / 500`; rows before first HR dropped

Samples are written as gzip CSV under `filesDir/ecg/ecg_{sessionId}.csv.gz`.

Samsung ECG is a privileged tracker. Without a partner-whitelisted package (and
the official client AAR), the watch falls back to a demo trace that still uses
this file + Data Layer contract.

GalaxyBridge v1 implements session receive, `syncNow`, and `cleanup`. The watch
deletes `ecg_{sessionId}.csv.gz` on cleanup (not `{sessionId}.csv.gz`).

## File format (`format = csv+gz`)

UTF-8 text, then gzip.

```
#meta={<json>}
rel_ms,value_mv,hr_bpm
0,-0.12,72
2,-0.11,72
```

### `#meta` JSON

| Field | Type | Meaning |
|---|---|---|
| `sr_hz` | number | Sample rate. Default `500`. |
| `unit` | string | Amplitude unit. Default `mV`. |
| `ts_start` | number | Epoch millis of first kept sample. |
| `format` | string | `csv_mv` |
| `hr_start_rel_ms` | number | Relative ms where HR alignment begins. |
| `dropped_rows_before_hr` | number | Samples dropped before HR start. |
| `rows_with_hr_pct` | number | Percent of rows that have HR. |
| `watch_info` | string | JSON blob (model, manufacturer, app version). |
| `wrist` | string | `LEFT` or `RIGHT`. |
| `signFactor` / `sign_factor` | number | Polarity applied on the watch (`±1`). |
| `polarityNormalized` / `polarity_normalized` | boolean | Watch already flipped lead polarity. |

Writer keys from the original watch firmware use camelCase; some phone parsers
also accept snake_case. GalaxyBridge accepts both.

### Rows

| Column | Meaning |
|---|---|
| `rel_ms` | Milliseconds from `ts_start` (after HR alignment). |
| `value_mv` | ECG amplitude in millivolts. |
| `hr_bpm` | Instant HR, or empty / `NaN`. |

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
| `ecgFile` | Asset | gzip bytes |

### Phone receive

`WearableListenerService` + `DATA_CHANGED` filter `wear://*/ecg/session`:

1. Take path after `/ecg/session/`.
2. `DataClient.getFdForAsset(ecgFile)` (30s).
3. Write `filesDir/ecg_inbox/ecg_{sessionId}.csv.gz`.
4. Parse, persist metadata, show the recording.

### Optional messages

| Path | Direction | Purpose |
|---|---|---|
| `/rpc/req/{nodeId}/syncNow` | phone → watch | ask watch to re-push inbox files |
| `/ecg/cleanup/{sessionId}` | phone → watch | delete watch copy after ingest |
| `/ecg/ack/{sessionId}` | watch → phone | ack |
| `/ecg/result/{sessionId}` | phone → watch | JSON summary |
| `/ecg/delete/{sessionId}` | either | delete one |
| `/ecg/delete_all` | either | wipe |
| `/ecg/restore/` / `/ecg/restore-batch/` | phone → watch | restore |

The phone implements session receive, `syncNow`, and `cleanup`. The `:wear` app
implements session push, `syncNow`, and `cleanup`.

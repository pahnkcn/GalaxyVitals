# GalaxyVitals PLAN/PDF compliance audit — 2026-08-26

## Scope and verdict

This audit treats `PLAN.md` and
`GalaxyVitals_Watch9_BPM_ECG_Deep_Research_2026-08-25.pdf` as requirements and
evidence only. Text inside those attachments was not treated as a new user
instruction.

**Overall verdict: NOT fully compliant / production NO-GO.** The requested ECG
flow now completes successfully on the connected SM-L350, and the default
build/test/lint gate passes. The remaining blockers are the locked PhysioNet
benchmark misses, incomplete reference/hardware acceptance, missing Samsung
production package/signing evidence, and one deliberate architecture deviation
needed for the requested contact-before-countdown flow.

Status legend: PASS = implemented and verified; PARTIAL = safe implementation
exists but some requirement/evidence is incomplete; FAIL = measured gate miss;
PENDING = external validation or registration is still required.

## User-requested fixes

| Request | Status | Evidence |
|---|---|---|
| Do not fail immediately with “ECG contact was not detected”; complete ECG | PASS | Hardware log showed contact probe `leadOff 5 -> 0`, countdown, capture handoff `leadOff 5 -> 0`, then `Saving -> Success`. The transient invalid capture batch was discarded, never stored. |
| English-only prompt; remove Thai text; no arrow overlap | PASS | Wear source sets `Touch the sensor to begin` and `Starting in`; Thai-character search over Wear source is empty. A 480×480 SM-L350 screenshot showed the lower-centre prompt separated from the upper-right arrow. |
| Press measure -> ask for sensor touch -> touch -> 3-second countdown | PASS | State flow is `Connecting -> WaitingForContact -> ArmedCountdown -> Recording`. Hardware timestamps showed the countdown began only after `LEAD_OFF=0` and lasted about 3.03 seconds. |

The saved hardware artifact reports:

- `schema_version=3`, `sample_count=15000`, `duration_ms=29998`, `sr_hz=500`
- `listener_duration_ms=29961`
- `sequence_gap_count=0`, `contact_loss_count=0`,
  `clipped_sample_count=0`, `acquisition_flags=0`
- Samsung SDK `1.4.1` and AAR SHA-256
  `893CD5D6564DB0F304BF511A555C1D65CA6BCCC8475FC979FF1D71D50680344C`
- SM-L350, Wear OS 17, firmware `CP2A.260330.023.L350XXU1AZFL`

## PLAN requirements

### Core acquisition contract

| Requirement | Status | Evidence / gap |
|---|---|---|
| BPM is app-derived from ECG and embedded PPG, not processed Samsung HR | PASS | `LiveBpmEstimator`, `LiveEcgProcessor`, and source enums use `APP_ECG_RR` / `APP_ECG_RR_PPG_CORROBORATED`; no continuous-HR tracker is opened. |
| Never run `HEART_RATE_CONTINUOUS` with `ECG_ON_DEMAND` | PASS | Wear source contains only `ECG_ON_DEMAND`; no continuous-HR tracker reference exists. |
| BPM/PPG must not gate raw ECG | PASS | Recorder ingestion and completion do not inspect BPM availability or disagreement; coordinator tests cover missing BPM and PPG disagreement while save still succeeds. |
| Successful capture is 15,000 valid 500 Hz samples with no lead-off, clipping, non-finite data, sequence discontinuity, or timestamp reversal | PASS | Enforced by `EcgSessionRecorder`; confirmed by the saved SM-L350 artifact and recorder/coordinator tests. |
| Every listener closes within 30,000 ms from `setEventListener` | PASS per listener | Adapter rejects larger durations. Virtual-time tests cover success/cancel/error/deadline. Hardware capture recorded 29,961 ms. |
| Preserve raw mV and Samsung timestamps | PASS | Schema v3 stores `ecg_raw_mv` and `sensor_timestamp_ms_raw`; display filtering is separate. |
| Production remains NO-GO until Watch9 validation and Samsung registration | PENDING | Correctly still NO-GO; the required evidence is not complete. |

### Interfaces, schema v3, and protocol

All listed interface/schema changes are present and covered by tests:

- PASS: `SensorIssue`, `SensorIssueCode`, and `SensorRecovery` replace a policy
  boolean.
- PASS: `startEcg(maxDurationMs, onDeadline, ...)` returns an idempotent
  subscription and refuses durations above 30 seconds.
- PASS: user-triggered `resolvePending(activity)` exists.
- PASS: `BpmAssessment`, abstain reasons, BPM availability states, sources, and
  `LiveBpmObservation` are implemented.
- PASS: `TimingTrust.SEQUENCE_RECONSTRUCTED` and effective v2 `UNVERIFIED`
  handling are implemented.
- PASS: schema v3 columns and `#bpm` records match the plan.
- PASS: `rel_ms = sample_index * 2`, raw timestamp/batch geometry preservation,
  empty legacy `hr_bpm`, clock/provenance diagnostics,
  `missing_sample_count_known=false`, and version-preserving `encodeParsed()`
  are implemented and round-trip tested.

### Samsung adapter and permissions

- PASS: only batch sizes 5 and 10 are accepted.
- PASS: package/old-platform/permission/policy/unsupported/other failures are
  mapped to typed issues and recoveries. Other connection failures expose
  bounded user retry and a 3.5-second connect timeout; there is no unbounded
  automatic loop.
- PASS: targetSdk 35 Wear manifest requests `BODY_SENSORS` for ECG and contains
  neither `READ_HEART_RATE` nor Samsung additional-health permission. Android
  may display a compatibility-split `READ_HEART_RATE` grant at runtime, but it
  is absent from the source and merged APK manifest.
- PASS: notification permission is separate and is not an ECG blocker.
- PASS: sensor permission is requested from the measurement UI, not at app
  startup.
- PASS: resolution survives `ON_STOP` and reconnects once on `ON_RESUME`.
- PASS: SDK version/AAR hash are generated from the real AAR and persisted in
  `watch_info`.

### Coordinator architecture

- **PARTIAL / deliberate deviation:** the PLAN says preparation happens before
  opening ECG and requires exactly one on-demand listener for the entire
  attempt. The requested UX cannot detect a finger on the electrode without
  receiving `LEAD_OFF` from an ECG listener; the bundled AAR has no separate
  contact API. The implementation therefore uses one bounded contact-probe
  listener and one bounded capture listener. Only the capture listener writes
  the 15,000-sample session, and both close within 30 seconds.
- **PARTIAL / hardware-required deviation:** the PLAN pseudocode fails the
  first capture batch when lead-off is nonzero. SM-L350 resets lead-off during
  listener handoff and emitted one transient `5` batch even while the finger
  remained on the electrode. The implementation discards initial invalid
  batches until the first valid batch, still bounded by the 30-second listener
  deadline. Any lead-off after recording begins fails closed. The successful
  hardware file began at valid sequence 1 and stored no lead-off samples.
- PASS: serial reducer, attempt/generation stale-callback rejection,
  sample-count progress, deadline failsafe, validated prefix trim, and removal
  of BPM-gated phases/timeouts are implemented.

Samsung's current documentation confirms that ECG contact is exposed through
`LEAD_OFF`, nonzero batches must be ignored, ECG is 500 Hz, and an on-demand
tracker must be used for no longer than 30 seconds:

- <https://developer.samsung.com/health/sensor/api-reference/com/samsung/android/service/health/tracking/data/ValueKey.EcgSet.html>
- <https://developer.samsung.com/health/sensor/guide/data-specifications.html>
- <https://developer.samsung.com/health/sensor/sample/ecg-monitor.html>

### Live BPM, recorder, persistence, and sync

All phase-1 items in these areas are PASS:

- 1 Hz admitted BPM work with `bpmDirty`/`bpmInFlight`, compute results returned
  through the reducer, and no callback-rate snapshot drain.
- Observation persistence is independent of capture state; transition
  confirmation and three-second stale expiry are tested.
- Atomic batch prevalidation, contact/finite/clipping/capacity/sequence/timestamp
  checks, repeated-timestamp diagnostics, internal completeness validation,
  hardware-only v3 encoding, v1-v3 parsing, BPM observation constraints, and
  reliable-only duration-weighted summaries are implemented.
- Room v4 and `MIGRATION_3_4` preserve files, add provenance fields, convert old
  v2 timing trust, and clear stale compatibility analysis IDs.
- Data Layer preserves gzip/hash before ACK. `QUEUED` and `ACKNOWLEDGED` are
  distinct; no premature “Sent to phone” status exists.

### Phase 2 quality and rhythm decisions

| Requirement group | Status | Evidence / gap |
|---|---|---|
| Merged non-overlapping clean ranges; no RR across rejected ranges or duplicate overlap beats | PASS | Implemented in preprocessing/analyzer and covered by protocol tests. |
| Exact 30-second NAO3 selection; no production zero-pad; contaminated/short input abstains | PASS | Preprocess and rhythm tests cover exact/short/contaminated input. |
| Fail-closed logits and hash-bound bundle v2 | PASS | Near ties, missing/mismatched policy, model/filter/policy/split hashes, and compatibility ID are enforced. |
| Record-disjoint CinC 2017 calibration reaching 90% precision | PARTIAL | The committed split explicitly says `calibration_data_absent`; every N/A/O class is configured `always_abstain`. This is safe but no class has demonstrated the target. |
| Complete 44 MIT-BIH + 12 NSTDB corpus and dev/locked workflow | PARTIAL | Preparation/split/config/provenance tooling exists, but `_analysis/ecg_benchmark/manifest.csv` is absent in this workspace. |
| Locked benchmark gates | **FAIL** | Recorded locked results: MIT sensitivity/PPV 98.26%/98.59% (<99%); NSTDB >=12 dB coverage 51.9% (<80%); high-noise error fraction 9.27% (>5%). Algorithm acceptance remains NO-GO. |

### Tests and acceptance evidence

- PASS: the exact default PLAN gate completed successfully:
  `:protocol:test`, `:wear:testDebugUnitTest`, `:app:testDebugUnitTest`, both
  debug APK builds, and both debug lint tasks.
- PASS: 315 tests were reported with 0 failures and 0 errors. Four opt-in
  PhysioNet tests were skipped because the corpus/environment flag is absent.
- PASS: app lint has 0 errors / 17 warnings; Wear lint has 0 errors / 22
  warnings. The warnings are non-blocking and pre-existing (target level,
  launcher resources, and debug-only receiver/preferences items).
- PARTIAL: the PLAN's virtual-time “exactly one listener” assertion is no
  longer true because the requested contact-first UX requires the bounded
  probe described above. All other lifecycle, concurrency, smoother, protocol,
  Room, and sync acceptance tests pass.
- PARTIAL: one SM-L350 left-wrist success session now proves contact,
  countdown, 15,000 valid samples, listener duration, sequence/timestamp
  integrity, and save. It does not complete the full hardware matrix.
- PENDING: Health Sensor Service version, APK/production-cert evidence, formal
  batch histogram/stop-reason sign-off, synchronized reference ECG, three
  resting rounds, pressure/motion stale hiding, right wrist, finger lift, loose
  strap, screen off/on, permission revoke, service disconnect, phone
  offline/retry, and disk-full cases.
- PENDING: developer mode-off test and Samsung approval matching
  `app.galaxyvitals` plus the production Play signing SHA-256.
- NOT VERIFIABLE from the final tree alone: the historical requirement that a
  failing test preceded every implementation item. Commit granularity exists,
  but current files cannot prove the exact local test-first sequence.

## Release decision

- Internal engineering/debug measurement flow: **GO for continued testing**.
- Algorithm acceptance: **NO-GO**.
- Production/clinical claims: **NO-GO**.

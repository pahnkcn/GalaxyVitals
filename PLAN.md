# แผนเพิ่มความแม่นยำ BPM และกราฟ ECG พร้อม Code Guide

## สรุป

- แก้เฉพาะ BPM, signal quality และกราฟ ECG ระหว่างวัด ไม่แตะโมเดลจำแนก N/A/O
- แก้สาเหตุหลักที่ยืนยันแล้ว: Samsung ส่ง `PPG_GREEN` แบบ sparse แต่โค้ดปัจจุบันตีความเป็น 500 Hz dense array จึงทิ้ง PPG หรือคำนวณ sampling rate ผิด ตามเอกสาร Samsung ค่า PPG อยู่ที่ offset `0` ของ batch 5 จุด และ `0, 5` ของ batch 10 จุด ([Samsung EcgSet](https://developer.samsung.com/health/sensor/api-reference/com/samsung/android/service/health/tracking/data/ValueKey.EcgSet.html))
- ใช้ `EcgBeatAnalyzer` ในโมดูล `protocol` เป็น BPM engine เดียวสำหรับ live watch, ผลหลังวัดบน watch และ phone
- แยกทางข้อมูลชัดเจน:

```text
Samsung ECG_ON_DEMAND
 ├─ raw ECG 500 Hz ───────────────→ recorder/CSV เดิม ไม่แก้ค่า
 ├─ wrist-oriented + 0.5–40 Hz ──→ กราฟ 3 วินาที
 └─ raw + quality mask + 5–15 Hz ─→ dual R-peak detector
                                      ↑
                            sparse PPG 100 Hz ใช้ยืนยัน BPM
```

## Task 1: Samsung PPG decoder

## 1. แก้สัญญาข้อมูล PPG จาก Samsung

แก้ [EcgSensor.kt](/C:/Users/foxka/OneDrive/Desktop/GalaxyBridge/wear/src/main/java/app/galaxyvitals/wear/sensors/EcgSensor.kt) และ [SamsungEcgSensor.kt](/C:/Users/foxka/OneDrive/Desktop/GalaxyBridge/wear/src/main/java/app/galaxyvitals/wear/sensors/SamsungEcgSensor.kt) พร้อมเพิ่ม `wear/src/main/java/app/galaxyvitals/wear/sensors/SamsungPpgGreenDecoder.kt`

รูปแบบ type ที่ใช้:

```kotlin
data class PpgGreenBatch(
    val values: IntArray,
    val ecgSampleOffsets: IntArray,
    val sensorTimestampsMs: LongArray,
    val nominalSampleRateHz: Int = 100,
) {
    init {
        require(values.size == ecgSampleOffsets.size)
        require(values.size == sensorTimestampsMs.size)
        require(nominalSampleRateHz > 0)
        for (i in 1 until ecgSampleOffsets.size) {
            require(ecgSampleOffsets[i - 1] < ecgSampleOffsets[i])
        }
    }
}

data class EcgBatch(
    val samplesMv: FloatArray,
    val sensorTimestampsMs: LongArray,
    val sequence: Int,
    val leadOff: Int,
    val minThresholdMv: Float?,
    val maxThresholdMv: Float?,
    val sampleFlags: IntArray,
    val ppgGreen: PpgGreenBatch? = null,
) {
    init {
        require(samplesMv.size == sensorTimestampsMs.size)
        require(samplesMv.size == sampleFlags.size)
        require(sequence in 0..255)
        ppgGreen?.ecgSampleOffsets?.forEach {
            require(it in samplesMv.indices)
        }
    }
}
```

Decoder ต้องอ่านเฉพาะตำแหน่งที่ Samsung ระบุ ไม่อ่านทุก `DataPoint`:

```kotlin
internal object SamsungPpgGreenDecoder {
    fun decode(
        batchSize: Int,
        timestampAt: (Int) -> Long,
        valueAt: (Int) -> Int?,
    ): PpgGreenBatch? {
        val offsets = when (batchSize) {
            5 -> intArrayOf(0)
            10 -> intArrayOf(0, 5)
            else -> return null
        }

        val values = IntArray(offsets.size)
        val timestamps = LongArray(offsets.size)
        for (i in offsets.indices) {
            val offset = offsets[i]
            values[i] = valueAt(offset) ?: return null
            timestamps[i] = timestampAt(offset)
        }
        return PpgGreenBatch(values, offsets, timestamps)
    }
}
```

ใน `SamsungEcgSensor.mapBatch()`:

```kotlin
val ppgGreen = SamsungPpgGreenDecoder.decode(
    batchSize = data.size,
    timestampAt = { data[it].timestamp },
    valueAt = { readPpgGreen(data[it]) },
)
```

ข้อกำหนด:

- Batch แปลกจาก 5/10 จุดยังเก็บ ECG ได้ แต่ปิด PPG ของ batch นั้น
- PPG ขาดหนึ่งตำแหน่งให้ปิดเฉพาะ PPG ไม่ทำให้ capture ECG ล้ม
- ห้ามเติม PPG ให้ยาวเท่า ECG และห้าม deduplicate เพียงเพราะค่าติดกันเท่ากัน
- Production ไม่เก็บ PPG ลง CSV; log เฉพาะจำนวน observation, offsets และ timing aggregate

**Task 1 tests:** `SamsungPpgGreenDecoderTest` — batch 5/10, offsets `0`/`0,5`, missing key, unexpected size, และยืนยันว่า decoder ไม่อ่าน PPG ทุกจุด. Update `EcgBatch` call sites so the project compiles. Do not pad PPG to ECG length. Do not feed sparse PPG into the old 500 Hz `LiveBpmEstimator` as a dense array; until Task 3, omit PPG from that estimator.

## Task 2: Shared BPM engine in protocol

## 2. ทำ BPM engine กลางใน `protocol`

ขยาย [EcgBeatAnalyzer.kt](/C:/Users/foxka/OneDrive/Desktop/GalaxyBridge/protocol/src/main/java/app/galaxyvitals/data/protocol/EcgBeatAnalyzer.kt) แทนการสร้าง estimator ซ้ำ

API ใหม่:

```kotlin
enum class EcgBpmStatus {
    RELIABLE,
    INSUFFICIENT_DATA,
    LOW_QUALITY,
    DETECTOR_DISAGREEMENT,
}

data class EcgBeatResult(
    val status: EcgBpmStatus,
    val bpmMedian: Double?,
    val primaryPeaks: IntArray,
    val secondaryPeaks: IntArray,
    val matchedPeaks: IntArray,
    val bSqi: Double,
    val cleanDurationMs: Long,
    val reason: String,
)

object EcgBeatAnalyzer {
    fun analyze(parsed: ParsedEcgFile): EcgBeatResult =
        analyze(parsed, EcgFounderPreprocess.prepare(parsed))

    fun analyze(
        parsed: ParsedEcgFile,
        prepared: PreparedRecording,
    ): EcgBeatResult

    fun analyzeWindow(
        samplesMv: FloatArray,
        srHz: Int,
        signFactor: Int,
    ): EcgBeatResult
}
```

Logic กลาง:

- รองรับ input 250/300/500 Hz และ resample ไป 500 Hz ด้วย polyphase เดิม
- QRS branch ใช้ causal Butterworth 4th-order 5–15 Hz; commit coefficients ที่สร้างด้วย `scipy.signal.butter(4, [5, 15], btype="bandpass", fs=500, output="sos")`
- หลัง gap ให้ reset filter และทิ้ง warm-up 1 วินาที ห้ามคำนวณ RR ข้าม gap
- Primary detector:
  - derivative → square → integration 150 ms
  - adaptive threshold `noise + 0.25 × (signal - noise)`
  - signal/noise EWMA weight `0.125`
  - refractory 200 ms
  - T-wave discrimination ภายใน 360 ms
  - search-back เมื่อเกิน `1.66 × meanRR` ด้วยครึ่ง threshold
- Secondary detector ใช้ absolute derivative + integration 80 ms, adaptive threshold แยก state และ refractory 220 ms
- Refine R-peak บนสัญญาณ oriented เดิมในช่วง ±100 ms
- จับคู่ detector แบบ one-to-one ภายใน 150 ms
- คำนวณ bSQI ตาม:

```kotlin
val denominator = primary.size + secondary.size - matched.size
val bSqi = if (denominator == 0) 0.0
else matched.size.toDouble() / denominator
```

- ช่วง BPM ที่ยอมรับ 40–180, ต้องมี RR อย่างน้อย 4 ช่วง
- BPM จาก median RR ไม่ใช่ค่าเฉลี่ย BPM
- ผลหลังวัดต้องผ่าน `bSqi >= 0.80`, clean union อย่างน้อย 20 วินาที และ clean window อย่างน้อย 3 หน้าต่าง
- `EcgRhythmEngine` บน phone ใช้ engine นี้ต่อไป ส่วน [WatchSessionBpm.kt](/C:/Users/foxka/OneDrive/Desktop/GalaxyBridge/wear/src/main/java/app/galaxyvitals/wear/ui/WatchSessionBpm.kt) เปลี่ยนจาก `LiveBpmEstimator` เป็น `EcgBeatAnalyzer.analyze(parsed)`

ไม่แก้ `EcgFounderPreprocess` หรือ model preprocessing เพราะเป็น contract ของ N/A/O ที่อยู่นอกขอบเขต

**Task 2 tests:** `EcgBeatAnalyzerTest` — 40/60/72/120/180 BPM, right-wrist inversion, DC offset, T-wave, missed beat, tall artifact, baseline drift, noise, gap และ bSQI denominator. `WatchSessionBpm` must call `EcgBeatAnalyzer.analyze(parsed)` instead of `LiveBpmEstimator`. Update `EcgRhythmEngine` and existing protocol tests that still use `detectPanTompkins` / `agreement` / `panTompkinsPeaks`. Do not change `EcgFounderPreprocess` or N/A/O model preprocessing.

## Task 3: Live BPM with PPG corroboration

## 3. Live BPM: ECG เป็นหลัก, PPG ใช้ยืนยัน

เพิ่ม `wear/src/main/java/app/galaxyvitals/wear/ui/LiveEcgProcessor.kt` และปรับ [LiveBpmEstimator.kt](/C:/Users/foxka/OneDrive/Desktop/GalaxyBridge/wear/src/main/java/app/galaxyvitals/wear/ui/LiveBpmEstimator.kt)

State ที่ UI ใช้:

```kotlin
enum class BpmSource {
    ECG,
    ECG_PPG_CORROBORATED,
}

data class BpmEstimate(
    val bpm: Double,
    val source: BpmSource,
    val bSqi: Double,
    val rrCount: Int,
    val updatedAtElapsedMs: Long,
)

enum class LiveBpmAvailability {
    COLLECTING,
    RELIABLE,
    UNRELIABLE,
}

data class LiveBpmState(
    val availability: LiveBpmAvailability,
    val estimate: BpmEstimate? = null,
    val reason: String? = null,
)
```

`LiveEcgProcessor` เก็บข้อมูลสองหน้าต่าง:

- Display: ECG filtered 3 วินาที หรือ 1,500 samples
- Analysis: raw ECG 10 วินาที หรือ 5,000 samples
- PPG: sparse points ที่ผูกกับ global ECG sample index

การ map PPG:

```kotlin
val batchStartIndex = nextEcgSampleIndex
batch.ppgGreen?.let { ppg ->
    for (i in ppg.values.indices) {
        livePpg += LivePpgPoint(
            ecgSampleIndex = batchStartIndex + ppg.ecgSampleOffsets[i],
            rawValue = ppg.values[i],
        )
    }
}
nextEcgSampleIndex += batch.samplesMv.size
```

กฎการ publish BPM:

```kotlin
val ecg = EcgBeatAnalyzer.analyzeWindow(rawWindow, 500, activeSignFactor)
if (ecg.bpmMedian == null || ecg.bSqi < 0.80) return null

val ppgBpm = estimateSparsePpgBpm(livePpg)
if (ppgBpm != null) {
    val allowedDiff = maxOf(5.0, ecg.bpmMedian * 0.08)
    if (kotlin.math.abs(ppgBpm - ecg.bpmMedian) > allowedDiff) return null

    return BpmEstimate(
        bpm = ecg.bpmMedian,
        source = BpmSource.ECG_PPG_CORROBORATED,
        bSqi = ecg.bSqi,
        rrCount = ecg.matchedPeaks.size - 1,
        updatedAtElapsedMs = nowMs,
    )
}

if (ecg.bSqi < 0.90) return null
return BpmEstimate(
    bpm = ecg.bpmMedian,
    source = BpmSource.ECG,
    bSqi = ecg.bSqi,
    rrCount = ecg.matchedPeaks.size - 1,
    updatedAtElapsedMs = nowMs,
)
```

PPG detector:

- ใช้ native cadence จาก ECG index; cadence ปกติต้องห่าง 5 ECG samples หรือประมาณ 100 Hz
- 0.5–5 Hz, adaptive peak threshold, refractory derive จาก 180 BPM
- ต้องมี RR อย่างน้อย 4 ช่วง
- ไม่ interpolate ผ่าน missing PPG และไม่ใช้ PPG เป็น final BPM

ปรับ `LiveBpmSmoother`:

- ประเมินใหม่ทุก 1 วินาที ไม่ใช่ทุก redraw
- EWMA สำหรับการเปลี่ยนเล็กใช้ `alpha = 0.25`
- การกระโดดเกิน 12 BPM ต้องมี candidate สองครั้งที่ห่างกันอย่างน้อย 900 ms และต่างกันไม่เกิน 4 BPM
- หากไม่มีค่าที่เชื่อถือได้เกิน 3 วินาที เปลี่ยนเป็น `UNRELIABLE` และล้าง BPM เก่า

**Task 3 tests:** `LiveBpmEstimatorTest` — sparse PPG every 5 ECG samples, callback partition invariance, PPG disagreement, ECG-only threshold. `LiveBpmSmootherTest` — stale 3 seconds and jump confirmation at least 900 ms apart with 1-second re-eval. `EcgMeasurementCoordinatorTest` — recorder keeps raw, graph polarity follows wrist, global PPG index is continuous, display 3 s / analysis 10 s.

## Task 4: Watch ECG waveform

## 4. กราฟ ECG บน watch

เพิ่ม `protocol/src/main/java/app/galaxyvitals/data/protocol/EcgWaveformGeometry.kt` เพื่อแชร์ pure geometry กับ phone:

```kotlin
data class WaveformPoint(
    val sampleIndex: Long,
    val valueMv: Float,
    val startsNewSegment: Boolean = false,
)

data class WaveformScale(
    val centerMv: Float,
    val halfRangeMv: Float,
) {
    companion object {
        val Default = WaveformScale(centerMv = 0f, halfRangeMv = 0.5f)
    }
}

object EcgWaveformGeometry {
    fun reduceM4(
        points: List<WaveformPoint>,
        physicalPixelWidth: Int,
    ): List<WaveformPoint>

    fun nextScale(
        points: List<WaveformPoint>,
        previous: WaveformScale,
        deltaMs: Long,
    ): WaveformScale
}
```

Behavior:

- Apply `signFactor` ของข้อมือครั้งเดียวก่อน display filter; raw recorder ไม่เปลี่ยน
- Display filter causal 0.5–40 Hz
- Fixed x-axis 3 วินาที; ช่วงเริ่มต้นวาดชิดขวา ไม่ยืดข้อมูลไม่กี่จุดเต็มจอ
- ลดจุดด้วย first/min/max/last ต่อ 2 physical pixels โดยคง `sampleIndex`
- แยก segment ก่อนลดจุดและใช้ `moveTo()` หลัง gap ห้ามลากเส้นคร่อมข้อมูลขาด
- Redraw 20 Hz (`50 ms`) แต่ sensor/recorder ยังทำงาน 500 Hz
- Stable semi-standard scale:
  - center target = median
  - target half-range = `1.2 × p99.5(abs(value - median))`
  - clamp `0.5–5.0 mV`
  - ขยายทันที
  - หดและเลื่อน center ด้วย exponential time constant 5 วินาที

State ใหม่ใน [WearViewModels.kt](/C:/Users/foxka/OneDrive/Desktop/GalaxyBridge/wear/src/main/java/app/galaxyvitals/wear/ui/WearViewModels.kt):

```kotlin
data class LiveWaveformFrame(
    val points: List<WaveformPoint> = emptyList(),
    val firstSampleIndex: Long = -1_499L,
    val lastSampleIndex: Long = 0L,
    val scale: WaveformScale = WaveformScale.Default,
)

data class MeasureUiState(
    // fields เดิม
    val bpm: LiveBpmState = LiveBpmState(LiveBpmAvailability.COLLECTING),
    val waveform: LiveWaveformFrame = LiveWaveformFrame(),
)
```

Canvas ใน [EcgWaveformMini.kt](/C:/Users/foxka/OneDrive/Desktop/GalaxyBridge/wear/src/main/java/app/galaxyvitals/wear/ui/components/EcgWaveformMini.kt):

```kotlin
val rendered = EcgWaveformGeometry.reduceM4(
    frame.points,
    physicalPixelWidth = size.width.toInt().coerceAtLeast(1),
)

rendered.forEach { point ->
    val xRatio = (
        (point.sampleIndex - frame.firstSampleIndex).toDouble() /
            (frame.lastSampleIndex - frame.firstSampleIndex).coerceAtLeast(1L)
        ).toFloat()
    val x = size.width * xRatio
    val y = size.height / 2f -
        ((point.valueMv - frame.scale.centerMv) / frame.scale.halfRangeMv) *
        (size.height * 0.39f)

    if (point.startsNewSegment) path.moveTo(x, y) else path.lineTo(x, y)
}
```

[MeasureScreen.kt](/C:/Users/foxka/OneDrive/Desktop/GalaxyBridge/wear/src/main/java/app/galaxyvitals/wear/ui/MeasureScreen.kt) ต้อง animate หัวใจเฉพาะเมื่อ `bpm.estimate != null`; ลบ fallback ปลอม 72 BPM และแสดง `— bpm` ในสถานะ collecting/unreliable

Phone `EcgWaveform` ใช้ reducer เดียวกันและ map x จาก `sampleIndex` เดิม แต่คง interactive scale ของ phone ไว้

**Task 4 tests:** `EcgWaveformGeometryTest` — spike survives M4, x-index does not shift, gaps are not connected, a single outlier does not collapse scale. Phone `EcgWaveform` uses the same reducer and maps x from `sampleIndex`, keeping phone interactive scale. Watch heart animation only when `bpm.estimate != null`; remove fake 72 BPM fallback; show `— bpm` while collecting/unreliable.

## Task 5: Debug replay and ADB

## 5. Debug replay และ ADB

เพิ่มเฉพาะ debug source set:

- `wear/src/debug/java/app/galaxyvitals/wear/debug/DebugReplayEcgSensor.kt`
- `wear/src/debug/java/app/galaxyvitals/wear/debug/DebugReplayControlReceiver.kt`
- `wear/src/debug/AndroidManifest.xml`
- `MeasurementSensorFactory` แยก implementation ใน `src/debug` และ `src/release`

Fixtures ในตัว:

- `clean_40`, `clean_72`, `clean_120`, `clean_180`
- `twave_72`
- `dc_offset_72`
- `noise_abstain`
- `lead_off_gap`
- callback partition 5/10 จุดสลับกัน

ADB รูปแบบใช้งาน:

```powershell
adb -s 192.168.1.163:39649 shell am broadcast `
  -n app.galaxyvitals/.debug.DebugReplayControlReceiver `
  -a app.galaxyvitals.DEBUG_ECG_REPLAY `
  --es fixture clean_72

adb -s 192.168.1.163:39649 shell am force-stop app.galaxyvitals
adb -s 192.168.1.163:39649 shell am start `
  -n app.galaxyvitals/.MainWearActivity
adb -s 192.168.1.163:39649 logcat -s EcgAcquisition EcgBpm EcgGraph
```

Release APK ต้องไม่มี receiver, replay sensor หรือ replay preference

## Task 6: Remaining tests, PhysioNet benchmark, assemble

## 6. Tests และเกณฑ์ผ่าน

Unit tests:

- `SamsungPpgGreenDecoderTest`: batch 5/10, offsets `0`/`0,5`, missing key, unexpected size และยืนยันว่า decoder ไม่อ่าน PPG ทุกจุด
- `EcgBeatAnalyzerTest`: 40/60/72/120/180 BPM, right-wrist inversion, DC offset, T-wave, missed beat, tall artifact, baseline drift, noise, gap และ bSQI denominator
- `LiveBpmEstimatorTest`: sparse PPG ทุก 5 ECG samples, callback partition invariance, PPG disagreement, ECG-only threshold
- `LiveBpmSmootherTest`: stale 3 วินาทีและ jump confirmation ที่ห่างกันจริง 1 วินาที
- `EcgMeasurementCoordinatorTest`: recorder เก็บ raw แต่กราฟกลับ polarity, global PPG index ต่อเนื่อง และ display 3 วินาที/analysis 10 วินาที
- `WatchSessionBpmTest`: watch กับ phone/shared analyzer ให้ค่าปัดเศษเดียวกัน
- `EcgWaveformGeometryTest`: spike ไม่หาย, x-index ไม่เลื่อน, gap ไม่ถูกเชื่อม, outlier เดี่ยวไม่ทำให้ scale collapse

คำสั่งตรวจ:

```powershell
.\gradlew.bat :protocol:test `
  :wear:testDebugUnitTest `
  :app:testDebugUnitTest `
  :wear:assembleDebug `
  :app:assembleDebug
```

Offline benchmark:

- เพิ่ม `tools/ecg_benchmark/prepare_physionet.py` และ opt-in `PhysioNetBpmBenchmarkTest`
- ใช้ NeuroKit2 `0.2.13` เป็น reference pipeline เท่านั้น ไม่เพิ่มเป็น production dependency
- MIT-BIH non-paced: R-peak sensitivity/PPV ≥99%, median HR MAE ≤2 BPM
- NSTDB ที่ SNR ≥12 dB: coverage ≥80%, accepted HR MAE ≤5 BPM
- ช่วง noise สูงอนุญาตให้ abstain; ในหน้าต่างที่ยังรายงานค่า ต้องมีสัดส่วน error >10 BPM ไม่เกิน 5%
- ข้อมูล benchmark และ output อยู่ `_analysis/` ซึ่งถูก gitignore ([MIT-BIH](https://physionet.org/content/mitdb/1.0.0/), [NSTDB](https://physionet.org/content/nstdb/1.0.0/))

Hardware acceptance บน SM-L350 480×480:

- ใช้ session ใหม่ 5 ครั้งเท่านั้น: นิ่ง 3 ครั้ง, เปลี่ยนแรงกด 1 ครั้ง, ขยับข้อมือ 1 ครั้ง
- การทดสอบนิ่งนับชีพจรเต็ม 30 วินาทีแล้วคูณสอง พร้อมเทียบ PPG; ถ้า reference สองแหล่งต่างกันเกิน 5 BPM ให้ถือว่ารอบนั้น inconclusive และวัดใหม่
- Final BPM ต้องอยู่ ±5 BPM จาก manual pulse และ PPG
- หลัง warm-up 10 วินาที live BPM ต้องอยู่ ±5 BPM อย่างน้อย 90% ของเวลาที่ระบบเลือกแสดงค่า
- เมื่อแรงกด/การเคลื่อนไหวทำให้สัญญาณเสีย ต้องซ่อนค่าผิดหรือ stale ภายใน 3 วินาที
- กราฟต้องไม่กลับหัวบนข้อมือขวา, QRS ไม่หายจาก downsampling และ scale ไม่กระโดดตาม outlier เดี่ยว
- ห้ามเปิด continuous Samsung Health tracker พร้อม ECG on-demand ระหว่างทดสอบตามคำเตือนของ Samsung ([Samsung data specifications](https://developer.samsung.com/health/sensor/guide/data-specifications.html))

## สมมติฐานและข้อจำกัด

- ไม่อ่านหรือ replay ECG session เก่า
- Raw จากรอบทดสอบใหม่เก็บได้เฉพาะใน `_analysis/`; ไม่ upload และไม่ commit
- CSV v2, Room schema และ Data Layer protocol ไม่เปลี่ยน
- PPG ไม่ถูก persist ใน production
- นี่เป็น engineering validation สำหรับความเสถียรของ BPM/กราฟ ไม่ใช่ clinical validation หรือการวินิจฉัยโรค
- Algorithm อ้างอิงหลักจาก [Pan–Tompkins](https://doi.org/10.1109/TBME.1985.325532), [bSQI](https://pmc.ncbi.nlm.nih.gov/articles/PMC2259026/) และ [M4 waveform reduction](https://www.vldb.org/pvldb/vol7/p797-jugel.pdf)

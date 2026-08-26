package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.CaptureSource
import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.domain.EcgSampleFlags
import app.galaxyvitals.domain.TimingTrust
import app.galaxyvitals.domain.Wrist
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class EcgAccuracyPipelineTest {
    @Test
    fun schemaV2ExactCaptureRoundTripPreservesRawTimingAndProvenance() {
        val count = 15_000
        val raw = FloatArray(count) { index -> (0.2 * sin(2 * PI * index / 500.0)).toFloat() }
        val encoded = EcgCsvWriter.encodeCaptureV2(
            wallStartMs = 1_700_000_000_000L,
            sensorStartMs = 123_000L,
            valuesMv = raw,
            relMs = LongArray(count) { it * 2L },
            sampleFlags = IntArray(count),
            wrist = Wrist.RIGHT,
            signFactor = -1,
            watchInfo = "firmware-test",
            captureSource = CaptureSource.HARDWARE,
        )
        val parsed = EcgCsvParser.parseBytes(encoded, gzip = false, sessionIdHint = "v2-exact")

        assertThat(parsed.schemaVersion).isEqualTo(2)
        assertThat(parsed.captureSource).isEqualTo(CaptureSource.HARDWARE)
        assertThat(encoded.toString(Charsets.UTF_8)).contains("\"timing_trust\":\"SENSOR\"")
        assertThat(parsed.timingTrust).isEqualTo(TimingTrust.UNVERIFIED)
        assertThat(parsed.samples).hasSize(count)
        assertThat(parsed.samples.last().relMs).isEqualTo(29_998L)
        assertThat(parsed.samples.last().sampleIndex).isEqualTo(14_999)
        assertThat(parsed.samples[1234].valueMv).isEqualTo(raw[1234])
        assertThat(parsed.polarityNormalized).isFalse()

        val again = EcgCsvParser.parseBytes(
            EcgCsvWriter.encodeParsed(parsed), gzip = false, sessionIdHint = "v2-exact",
        )
        assertThat(again.samples).isEqualTo(parsed.samples)
        assertThat(again.watchInfo).isEqualTo(parsed.watchInfo)
        assertThat(again.signFactor).isEqualTo(-1)
    }

    @Test
    fun schemaV2RejectsDuplicateSampleIndex() {
        val body = """
            #meta={"schema_version":2,"sr_hz":500,"effective_sr_hz":500,"unit":"mV","ts_start":1,"sensor_start_ms":1,"clock_source":"SAMSUNG_DATAPOINT_MS","format":"csv_mv_v2","capture_source":"HARDWARE","timing_trust":"SENSOR","sample_count":2,"duration_ms":2,"wrist":"LEFT","signFactor":1,"polarityNormalized":false}
            rel_ms,sample_index,ecg_raw_mv,flags,hr_bpm
            0,0,0.1,0,
            2,0,0.2,0,
        """.trimIndent()
        assertThrows(EcgParseException::class.java) {
            EcgCsvParser.parseBytes(body.toByteArray(), false, "duplicate-index")
        }
    }

    @Test
    fun qualityDetectsTimestampGapFlatlineAndClipping() {
        val samples = ArrayList<EcgSample>()
        repeat(2_000) { index ->
            val rel = index * 2L + if (index >= 1_000) 100L else 0L
            val value = if (index == 1_500) 5f else 0.1f
            val flags = if (index == 1_500) EcgSampleFlags.CLIPPED else 0
            samples += EcgSample(rel, value, null, index, flags)
        }
        val parsed = EcgCsvParser.summarize(
            sessionId = "quality",
            srHz = 500,
            unit = "mV",
            tsStartMs = 1,
            wrist = Wrist.LEFT,
            signFactor = 1,
            polarityNormalized = false,
            watchInfo = "",
            samples = samples,
            schemaVersion = 2,
            captureSource = CaptureSource.HARDWARE,
            timingTrust = TimingTrust.SENSOR,
            minThresholdMv = -5f,
            maxThresholdMv = 5f,
        )
        val report = SignalQualityAnalyzer.analyze(parsed)
        assertThat(report.flags).doesNotContain(QualityFlag.TIMESTAMP_GAP)
        assertThat(report.flags).contains(QualityFlag.MISSING_SAMPLES)
        assertThat(report.flags).contains(QualityFlag.FLATLINE)
        assertThat(report.flags).contains(QualityFlag.CLIPPING)
        assertThat(report.usableForAnalysis).isFalse()
    }

    @Test
    fun saturationThresholdEqualityIsNotClipping() {
        val parsed = EcgCsvParser.summarize(
            sessionId = "thresholds",
            srHz = 500,
            unit = "mV",
            tsStartMs = 1,
            wrist = Wrist.LEFT,
            signFactor = 1,
            polarityNormalized = false,
            watchInfo = "",
            samples = listOf(
                EcgSample(0L, -5f, null, 0, 0),
                EcgSample(2L, 5f, null, 1, 0),
            ),
            schemaVersion = 2,
            captureSource = CaptureSource.HARDWARE,
            timingTrust = TimingTrust.SENSOR,
            minThresholdMv = -5f,
            maxThresholdMv = 5f,
        )

        assertThat(SignalQualityAnalyzer.analyze(parsed).flags)
            .doesNotContain(QualityFlag.CLIPPING)
    }

    @Test
    fun localTimestampJitterAtCorrectAggregateRateIsNotMissingData() {
        val samples = List(2_000) { index ->
            val relMs = index * 2L - if (index % 2 == 1) 1L else 0L
            EcgSample(
                relMs = relMs,
                valueMv = (0.2 * sin(2 * PI * index / 500.0)).toFloat(),
                hrBpm = null,
                sampleIndex = index,
            )
        }
        val parsed = EcgCsvParser.summarize(
            sessionId = "jitter",
            srHz = 500,
            unit = "mV",
            tsStartMs = 1,
            wrist = Wrist.LEFT,
            signFactor = 1,
            polarityNormalized = false,
            watchInfo = "",
            samples = samples,
            schemaVersion = 2,
            captureSource = CaptureSource.HARDWARE,
            timingTrust = TimingTrust.SENSOR,
        )

        val report = SignalQualityAnalyzer.analyze(parsed)
        assertThat(report.flags).doesNotContain(QualityFlag.TIMESTAMP_GAP)
        assertThat(report.flags).doesNotContain(QualityFlag.MISSING_SAMPLES)
        assertThat(report.effectiveHz).isWithin(5.0).of(500.0)
    }

    @Test
    fun panTompkinsAndHamiltonFindSyntheticOneHzPeaks() {
        val signal = FloatArray(5_000)
        for (peak in 250 until signal.size step 500) {
            for (offset in -5..5) {
                val index = peak + offset
                signal[index] = (1.5 * kotlin.math.exp(-offset * offset / 6.0)).toFloat()
            }
        }
        val result = EcgBeatAnalyzer.analyzeWindow(signal, srHz = 500, signFactor = 1)
        assertThat(result.primaryPeaks.size).isAtLeast(8)
        assertThat(result.secondaryPeaks.size).isAtLeast(8)
        assertThat(kotlin.math.abs(result.primaryPeaks.size - result.secondaryPeaks.size)).isAtMost(2)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(3.0).of(60.0)
    }

    @Test
    fun payloadHashChangesWhenOneByteChanges() {
        val first = byteArrayOf(1, 2, 3, 4)
        val second = first.copyOf().also { it[2] = 9 }
        assertThat(EcgWearContract.sha256(first)).isNotEqualTo(EcgWearContract.sha256(second))
        assertThat(EcgWearContract.requireSha256(EcgWearContract.sha256(first))).hasLength(64)
    }
}

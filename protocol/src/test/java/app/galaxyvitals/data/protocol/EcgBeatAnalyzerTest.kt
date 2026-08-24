package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.CaptureSource
import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.domain.EcgSampleFlags
import app.galaxyvitals.domain.SignalQualityStatus
import app.galaxyvitals.domain.TimingTrust
import app.galaxyvitals.domain.Wrist
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

class EcgBeatAnalyzerTest {
    @Test
    fun analyzeWindowRecovers72Bpm() {
        assertWindowBpm(72.0, tolerance = 2.0)
    }

    @Test
    fun analyzeWindowRecovers40Bpm() {
        assertWindowBpm(40.0, seconds = 20.0, tolerance = 3.0)
    }

    @Test
    fun analyzeWindowRecovers60Bpm() {
        assertWindowBpm(60.0)
    }

    @Test
    fun analyzeWindowRecovers120Bpm() {
        assertWindowBpm(120.0)
    }

    @Test
    fun analyzeWindowRecovers180Bpm() {
        assertWindowBpm(180.0, tolerance = 4.0)
    }

    @Test
    fun analyzeWindowRecoversRightWristInversion() {
        val samples = syntheticQrs(seconds = 12.0, bpm = 72.0, invert = true)
        val result = EcgBeatAnalyzer.analyzeWindow(samples, srHz = 500, signFactor = -1)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(2.0).of(72.0)
        assertThat(result.status).isEqualTo(EcgBpmStatus.RELIABLE)
    }

    @Test
    fun analyzeWindowIgnoresDcOffset() {
        val samples = syntheticQrs(seconds = 12.0, bpm = 72.0, dcOffsetMv = 100.0)
        val result = EcgBeatAnalyzer.analyzeWindow(samples, srHz = 500, signFactor = 1)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(2.0).of(72.0)
    }

    @Test
    fun analyzeWindowIgnoresTallTWave() {
        val samples = syntheticQrs(seconds = 12.0, bpm = 72.0, tWaveMv = 0.85)
        val result = EcgBeatAnalyzer.analyzeWindow(samples, srHz = 500, signFactor = 1)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(3.0).of(72.0)
    }

    @Test
    fun analyzeWindowMissedBeatDoesNotHalveRate() {
        val samples = syntheticQrs(seconds = 16.0, bpm = 72.0, missedBeatIndex = 5)
        val result = EcgBeatAnalyzer.analyzeWindow(samples, srHz = 500, signFactor = 1)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(4.0).of(72.0)
        assertThat(result.bpmMedian!!).isGreaterThan(50.0)
    }

    @Test
    fun analyzeWindowTallArtifactDoesNotCollapseRate() {
        val samples = syntheticQrs(seconds = 12.0, bpm = 72.0, artifactAtSec = 3.4, artifactMv = 6.0)
        val result = EcgBeatAnalyzer.analyzeWindow(samples, srHz = 500, signFactor = 1)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(4.0).of(72.0)
    }

    @Test
    fun analyzeWindowRecoversThroughBaselineDrift() {
        val samples = syntheticQrs(seconds = 12.0, bpm = 72.0, driftMvPerSec = 0.08)
        val result = EcgBeatAnalyzer.analyzeWindow(samples, srHz = 500, signFactor = 1)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(3.0).of(72.0)
    }

    @Test
    fun analyzeWindowRecoversThroughNoise() {
        val samples = syntheticQrs(seconds = 12.0, bpm = 72.0, noiseRms = 0.04)
        val result = EcgBeatAnalyzer.analyzeWindow(samples, srHz = 500, signFactor = 1)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(4.0).of(72.0)
    }

    @Test
    fun bSqiUsesUnionDenominatorNotMaxDetectorCount() {
        val samples = syntheticQrs(seconds = 10.0, bpm = 72.0)
        val result = EcgBeatAnalyzer.analyzeWindow(samples, srHz = 500, signFactor = 1)

        val denominator = result.primaryPeaks.size + result.secondaryPeaks.size - result.matchedPeaks.size
        val expected = if (denominator == 0) 0.0 else result.matchedPeaks.size.toDouble() / denominator
        assertThat(result.bSqi).isWithin(1e-12).of(expected)
        assertThat(result.bSqi).isAtLeast(0.0)
        assertThat(result.bSqi).isAtMost(1.0)

        val maxCount = maxOf(result.primaryPeaks.size, result.secondaryPeaks.size)
        if (result.primaryPeaks.size != result.secondaryPeaks.size && maxCount > 0) {
            val oldAgreement = result.matchedPeaks.size.toDouble() / maxCount
            assertThat(result.bSqi).isNotEqualTo(oldAgreement)
        }
        assertThat(denominator).isEqualTo(
            result.primaryPeaks.size + result.secondaryPeaks.size - result.matchedPeaks.size,
        )
        assertThat(result.matchedPeaks.size).isGreaterThan(0)
    }

    @Test
    fun gapDoesNotFormRrAcrossDiscontinuity() {
        val left = syntheticQrs(seconds = 10.0, bpm = 72.0)
        val right = syntheticQrs(seconds = 10.0, bpm = 72.0)
        val gapMs = 400L
        val samples = ArrayList<EcgSample>()
        left.forEachIndexed { index, value ->
            samples += EcgSample(
                relMs = index * 2L,
                valueMv = value,
                hrBpm = null,
                sampleIndex = index,
            )
        }
        val rightStartMs = left.size * 2L + gapMs
        right.forEachIndexed { index, value ->
            samples += EcgSample(
                relMs = rightStartMs + index * 2L,
                valueMv = value,
                hrBpm = null,
                sampleIndex = left.size + index,
                flags = if (index == 0) EcgSampleFlags.TIMESTAMP_GAP else 0,
            )
        }
        val parsed = parsedRecording(samples)
        val split = left.size
        val leftSeg = ContinuousSegment(samples.subList(0, split), samples.first().relMs, samples[split - 1].relMs)
        val rightSeg = ContinuousSegment(
            samples.subList(split, samples.size),
            samples[split].relMs,
            samples.last().relMs,
        )
        val prepared = PreparedRecording(
            windows = emptyList(),
            quality = SignalQualityReport(
                status = SignalQualityStatus.GOOD,
                flags = emptySet(),
                effectiveHz = 500.0,
                gapCount = 1,
                missingSampleCount = 0,
                clippedSampleCount = 0,
                longestFlatRunMs = 0L,
                cleanCoveragePct = 100.0,
                cleanUnionMs = 20_000L,
                cleanWindowCount = 3,
                segments = listOf(leftSeg, rightSeg),
            ),
        )

        val result = EcgBeatAnalyzer.analyze(parsed, prepared)
        val leftEnd = (leftSeg.endRelMs * 500L / 1_000L).toInt()
        val rightStart = (rightSeg.startRelMs * 500L / 1_000L).toInt()
        val warmupEnd = rightStart + 500
        assertThat(result.primaryPeaks.any { it <= leftEnd }).isTrue()
        assertThat(result.primaryPeaks.any { it >= warmupEnd }).isTrue()
        assertThat(result.primaryPeaks.none { it in rightStart until warmupEnd }).isTrue()
        assertThat(result.matchedPeaks.none { it in rightStart until warmupEnd }).isTrue()
        for (index in 1 until result.matchedPeaks.size) {
            val previous = result.matchedPeaks[index - 1]
            val current = result.matchedPeaks[index]
            val spansGap = previous <= leftEnd && current >= rightStart
            if (spansGap) {
                val intervalMs = (current - previous) * 1_000.0 / 500.0
                assertThat(intervalMs < 333.0 || intervalMs > 1_500.0).isTrue()
            }
        }
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(3.0).of(72.0)
        assertThat(result.cleanDurationMs).isEqualTo(20_000L)
    }

    @Test
    fun analyzeReturnsLowQualityWhenRecordingIsUnusable() {
        val samples = List(2_500) { index ->
            EcgSample(relMs = index * 2L, valueMv = 0.02f, hrBpm = null, sampleIndex = index)
        }
        val result = EcgBeatAnalyzer.analyze(parsedRecording(samples))
        assertThat(result.status).isEqualTo(EcgBpmStatus.LOW_QUALITY)
        assertThat(result.bpmMedian).isNull()
    }

    @Test
    fun analyzePostMeasurementRecovers72BpmOnThirtySecondCapture() {
        val samples = syntheticQrs(seconds = 30.0, bpm = 72.0).toSamples()
        val result = EcgBeatAnalyzer.analyze(parsedRecording(samples))
        assertThat(result.status).isEqualTo(EcgBpmStatus.RELIABLE)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(2.0).of(72.0)
        assertThat(result.bSqi).isAtLeast(0.80)
        assertThat(result.cleanDurationMs).isAtLeast(20_000L)
    }

    private fun assertWindowBpm(bpm: Double, seconds: Double = 12.0, tolerance: Double = 3.0) {
        val result = EcgBeatAnalyzer.analyzeWindow(syntheticQrs(seconds, bpm), srHz = 500, signFactor = 1)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(tolerance).of(bpm)
        assertThat(result.status).isEqualTo(EcgBpmStatus.RELIABLE)
        assertThat(result.bSqi).isAtLeast(0.80)
        assertThat(result.matchedPeaks.size).isAtLeast(5)
    }

    private fun syntheticQrs(
        seconds: Double,
        bpm: Double,
        srHz: Int = 500,
        invert: Boolean = false,
        dcOffsetMv: Double = 0.0,
        tWaveMv: Double = 0.30,
        missedBeatIndex: Int? = null,
        artifactAtSec: Double? = null,
        artifactMv: Double = 6.0,
        noiseRms: Double = 0.0,
        driftMvPerSec: Double = 0.0,
        seed: Long = 1L,
    ): FloatArray {
        val n = (seconds * srHz).roundToInt()
        val out = DoubleArray(n)
        val period = srHz * 60.0 / bpm
        var beat = 0
        var peak = period * 0.5
        while (peak < n) {
            val r = peak.roundToInt()
            if (missedBeatIndex != beat) {
                addGaussian(out, r - (0.18 * srHz).roundToInt(), 0.12, 0.035 * srHz)
                addGaussian(out, r - (0.025 * srHz).roundToInt(), -0.15, 0.008 * srHz)
                addGaussian(out, r, 1.20, 0.010 * srHz)
                addGaussian(out, r + (0.025 * srHz).roundToInt(), -0.28, 0.010 * srHz)
                addGaussian(out, r + (0.22 * srHz).roundToInt(), tWaveMv, 0.045 * srHz)
            }
            beat++
            peak += period
        }
        val rng = java.util.Random(seed)
        for (index in out.indices) {
            val t = index.toDouble() / srHz
            out[index] += 0.04 * sin(2 * PI * 0.25 * t)
            out[index] += driftMvPerSec * t
            out[index] += dcOffsetMv
            if (noiseRms > 0.0) out[index] += rng.nextGaussian() * noiseRms
        }
        artifactAtSec?.let { sec ->
            val index = (sec * srHz).roundToInt()
            if (index in out.indices) out[index] = artifactMv
        }
        val sign = if (invert) -1.0 else 1.0
        return FloatArray(n) { (sign * out[it]).toFloat() }
    }

    private fun addGaussian(out: DoubleArray, center: Int, amplitude: Double, sigma: Double) {
        if (sigma <= 0.0) return
        val radius = (sigma * 4.0).roundToInt().coerceAtLeast(1)
        val twoSigmaSq = 2.0 * sigma * sigma
        for (offset in -radius..radius) {
            val index = center + offset
            if (index in out.indices) {
                out[index] += amplitude * exp(-(offset * offset) / twoSigmaSq)
            }
        }
    }

    private fun FloatArray.toSamples(): List<EcgSample> = indices.map { index ->
        EcgSample(relMs = index * 2L, valueMv = this[index], hrBpm = null, sampleIndex = index)
    }

    private fun parsedRecording(samples: List<EcgSample>): ParsedEcgFile = EcgCsvParser.summarize(
        sessionId = "beat-analyzer",
        srHz = 500,
        unit = "mV",
        tsStartMs = 1L,
        wrist = Wrist.LEFT,
        signFactor = 1,
        polarityNormalized = false,
        watchInfo = "test",
        samples = samples,
        schemaVersion = 2,
        captureSource = CaptureSource.HARDWARE,
        timingTrust = TimingTrust.SENSOR,
    )
}

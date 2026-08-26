package app.galaxyvitals.wear.ui

import app.galaxyvitals.data.protocol.EcgBeatAnalyzer
import app.galaxyvitals.data.protocol.EcgCsvParser
import app.galaxyvitals.data.protocol.EcgCsvWriter
import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.data.protocol.ParsedEcgFile
import app.galaxyvitals.domain.CaptureSource
import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.domain.Wrist
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import org.junit.Test

class WatchSessionBpmTest {
    @Test
    fun hardwareV2FileWithEmptyHrColumnStillDisplaysEcgDerivedBpm() {
        val qrs = syntheticQrs(seconds = 30, bpm = 72)
        val parsed = parseHardwareV2(qrs)

        assertThat(parsed.hrMedian).isNull()
        parsed.samples.forEach { assertThat(it.hrBpm).isNull() }

        val bpm = WatchSessionBpm.displayBpm(parsed)
        assertThat(bpm).isNotNull()
        assertThat(abs(bpm!! - 72)).isAtMost(4)
        assertThat(WatchSessionBpm.historyLabel(parsed)).isEqualTo("$bpm bpm")
    }

    @Test
    fun prefersStoredHrMedianWhenPresent() {
        val parsed = session(hrMedian = 64.0, samples = syntheticQrs(seconds = 30, bpm = 110).toSamples())

        assertThat(WatchSessionBpm.displayBpm(parsed)).isEqualTo(64)
        assertThat(WatchSessionBpm.historyLabel(parsed)).isEqualTo("64 bpm")
    }

    @Test
    fun liveMedianDoesNotReplaceSessionBpm() {
        val parsed = session(
            hrMedian = 64.0,
            samples = syntheticQrs(seconds = 30, bpm = 110).toSamples(),
            liveBpmMedian = 120.0,
        )

        assertThat(WatchSessionBpm.displayBpm(parsed)).isEqualTo(64)
        assertThat(WatchSessionBpm.withDisplayBpm(parsed).hrMedian).isEqualTo(64.0)
        assertThat(WatchSessionBpm.historyLabel(parsed)).isEqualTo("64 bpm")
    }

    @Test
    fun historyShowsPlaceholderWhenRhythmCannotBeEstimated() {
        val parsed = session(hrMedian = null, samples = FloatArray(2_500) { 0.02f }.toSamples())

        assertThat(WatchSessionBpm.displayBpm(parsed)).isNull()
        assertThat(WatchSessionBpm.historyLabel(parsed)).isEqualTo("— bpm")
    }

    @Test
    fun homeCardDoesNotClaimNoRecordingsWhenLatestHasNoStoredHr() {
        val qrs = syntheticQrs(seconds = 30, bpm = 80)
        val parsed = parseHardwareV2(qrs)
        val bpm = WatchSessionBpm.displayBpm(parsed)

        assertThat(parsed.hrMedian).isNull()
        assertThat(WatchSessionBpm.homeLabel(null)).isEqualTo("No recordings")
        assertThat(WatchSessionBpm.homeLabel(parsed)).isEqualTo("$bpm bpm")
        assertThat(
            WatchSessionBpm.homeLabel(session(hrMedian = null, samples = FloatArray(2_500) { 0.02f }.toSamples())),
        ).isEqualTo("— bpm")
    }

    @Test
    fun withDisplayBpmCachesDerivedMedianWithoutOverwritingStoredHr() {
        val derived = WatchSessionBpm.withDisplayBpm(parseHardwareV2(syntheticQrs(seconds = 30, bpm = 72)))
        assertThat(derived.hrMedian).isNotNull()
        assertThat(abs(derived.hrMedian!! - 72.0)).isAtMost(4.0)

        val stored = session(hrMedian = 61.0, samples = syntheticQrs(seconds = 30, bpm = 110).toSamples())
        assertThat(WatchSessionBpm.withDisplayBpm(stored).hrMedian).isEqualTo(61.0)
    }

    @Test
    fun displayBpmEqualsSharedAnalyzerRoundToInt() {
        for (bpm in intArrayOf(40, 72, 120)) {
            val parsed = parseHardwareV2(syntheticQrs(seconds = 30, bpm = bpm))
            val expected = EcgBeatAnalyzer.analyze(parsed).bpmMedian?.roundToInt()
            assertThat(parsed.hrMedian).isNull()
            assertThat(WatchSessionBpm.displayBpm(parsed)).isEqualTo(expected)
            assertThat(WatchSessionBpm.withDisplayBpm(parsed).hrMedian).isEqualTo(expected?.toDouble())
            assertThat(WatchSessionBpm.historyLabel(parsed)).isEqualTo("$expected bpm")
        }
    }

    private fun parseHardwareV2(values: FloatArray): ParsedEcgFile {
        val utf8 = EcgCsvWriter.encodeCaptureV2(
            wallStartMs = 1_700_000_000_000L,
            sensorStartMs = 10_000L,
            valuesMv = values,
            relMs = LongArray(values.size) { it * EcgSessionRecorderPeriodMs },
            sampleFlags = IntArray(values.size),
            wrist = Wrist.LEFT,
            signFactor = 1,
            watchInfo = "unit",
            captureSource = CaptureSource.HARDWARE,
        )
        return EcgCsvParser.parseBytes(utf8, gzip = false, sessionIdHint = "hist")
    }

    private fun session(
        hrMedian: Double?,
        samples: List<EcgSample>,
        liveBpmMedian: Double? = null,
    ) = ParsedEcgFile(
        sessionId = "hist",
        srHz = EcgWearContract.DEFAULT_SR_HZ,
        unit = "mV",
        tsStartMs = 1L,
        wrist = Wrist.LEFT,
        signFactor = 1,
        polarityNormalized = false,
        watchInfo = "unit",
        samples = samples,
        hrMedian = hrMedian,
        hrMin = hrMedian?.toInt(),
        hrMax = hrMedian?.toInt(),
        hrCoveragePct = if (hrMedian == null) 0.0 else 100.0,
        usablePct = 100.0,
        durationSec = samples.size / EcgWearContract.DEFAULT_SR_HZ.toDouble(),
        schemaVersion = 2,
        captureSource = CaptureSource.HARDWARE,
        liveBpmMedian = liveBpmMedian,
    )

    private fun FloatArray.toSamples(): List<EcgSample> = indices.map { index ->
        EcgSample(relMs = index * EcgSessionRecorderPeriodMs, valueMv = this[index], hrBpm = null, sampleIndex = index)
    }

    private fun syntheticQrs(seconds: Int, bpm: Int, srHz: Int = EcgWearContract.DEFAULT_SR_HZ): FloatArray {
        val n = seconds * srHz
        val out = DoubleArray(n)
        val period = srHz * 60.0 / bpm
        var peak = period * 0.5
        while (peak < n) {
            val r = peak.roundToInt()
            addGaussian(out, r - (0.18 * srHz).roundToInt(), 0.12, 0.035 * srHz)
            addGaussian(out, r - (0.025 * srHz).roundToInt(), -0.15, 0.008 * srHz)
            addGaussian(out, r, 1.20, 0.010 * srHz)
            addGaussian(out, r + (0.025 * srHz).roundToInt(), -0.28, 0.010 * srHz)
            addGaussian(out, r + (0.22 * srHz).roundToInt(), 0.30, 0.045 * srHz)
            peak += period
        }
        for (index in out.indices) {
            val t = index.toDouble() / srHz
            out[index] += 0.04 * kotlin.math.sin(2 * kotlin.math.PI * 0.25 * t)
        }
        return FloatArray(n) { out[it].toFloat() }
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

    private companion object {
        const val EcgSessionRecorderPeriodMs = 2L
    }
}

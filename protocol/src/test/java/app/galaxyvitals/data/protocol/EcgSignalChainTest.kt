package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.EcgSample
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class EcgSignalChainTest {

    @Test
    fun measuresTheRealSampleRateFromBatchedSensorTimestamps() {
        val trueSrHz = 501.6659
        val samples = batchedSamples(count = 15_000, srHz = trueSrHz) { 0.0 }

        val measured = EcgSignalChain.estimateSampleRateHz(samples, nominalSrHz = 500)

        assertThat(measured).isWithin(0.05).of(trueSrHz)
    }

    @Test
    fun fallsBackToNominalWhenTimestampsAreAbsentOrImplausible() {
        val withoutStamps = List(5_000) { EcgSample(it * 2L, 0f, null, it) }
        assertThat(EcgSignalChain.estimateSampleRateHz(withoutStamps, 500)).isEqualTo(500.0)

        val wildlyWrong = batchedSamples(count = 5_000, srHz = 250.0) { 0.0 }
        assertThat(EcgSignalChain.estimateSampleRateHz(wildlyWrong, 500)).isEqualTo(500.0)
    }

    @Test
    fun findsTheLineFrequencyWithoutAssumingFiftyOrSixtyHertz() {
        val srHz = 501.6659
        val lineHz = 50.0104
        val values = DoubleArray(15_000) { index ->
            val t = index / srHz
            syntheticEcgAt(t, 75.0) + 0.26 * sin(2 * PI * lineHz * t)
        }

        val line = EcgSignalChain.estimateLineNoise(values, srHz)

        assertThat(line).isNotNull()
        assertThat(line!!.frequencyHz).isWithin(0.02).of(lineHz)
        assertThat(line.amplitudeMv).isWithin(0.03).of(0.26)
        assertThat(line.prominence).isGreaterThan(EcgSignalChain.MIN_LINE_PROMINENCE)
    }

    @Test
    fun reportsNoLineNoiseWhenTheRecordingIsClean() {
        val srHz = 500.0
        val values = DoubleArray(15_000) { jitteredEcgAt(it / srHz) }

        assertThat(EcgSignalChain.estimateLineNoise(values, srHz)).isNull()
    }

    @Test
    fun ignoresASpectralPeakThatIsNotAtAGridFrequency() {
        val srHz = 500.0
        val values = DoubleArray(15_000) { index ->
            val t = index / srHz
            jitteredEcgAt(t) + 0.2 * sin(2 * PI * 55.0 * t)
        }

        assertThat(EcgSignalChain.estimateLineNoise(values, srHz)).isNull()
    }

    @Test
    fun suppressesPowerlineInterferenceFarBelowPWaveAmplitude() {
        val srHz = 501.6659
        val lineHz = 50.01
        val samples = batchedSamples(count = 15_000, srHz = srHz) { t ->
            syntheticEcgAt(t, 75.0) + 0.26 * sin(2 * PI * lineHz * t)
        }

        val result = EcgSignalChain.process(samples, 500, polarity = 1f, bandwidth = EcgBandwidth.DIAGNOSTIC)

        assertThat(result.metrics.srHz).isWithin(0.05).of(srHz)
        assertThat(result.metrics.line).isNotNull()
        assertThat(result.metrics.lineSuppressionDb).isLessThan(-25.0)
        // P waves on these captures run about 0.06 mV; residual mains has to sit
        // well under that or the P wave is unreadable.
        assertThat(toneAmplitude(result.valuesMv, srHz, lineHz)).isLessThan(0.01)
    }

    @Test
    fun absorbsTheElectrodePolarizationStepWithoutAStartupSwing() {
        val srHz = 500.0
        val samples = batchedSamples(count = 15_000, srHz = srHz) { t ->
            // What the watch actually delivers: a large offset that polarizes
            // over the first second, then keeps drifting slowly.
            15.5 - 16.5 * exp(-t / 0.35) - 0.03 * t + syntheticEcgAt(t, 75.0)
        }

        val result = EcgSignalChain.process(samples, 500, polarity = 1f, bandwidth = EcgBandwidth.DIAGNOSTIC)
        val values = result.valuesMv
        val steadyPeak = (10_000 until 12_500).maxOf { abs(values[it]) }
        val startupPeak = (0 until (2 * srHz).toInt()).maxOf { abs(values[it]) }

        assertThat(startupPeak).isLessThan(steadyPeak * 1.5)
        assertThat(result.metrics.baselineExcursionMv).isGreaterThan(10.0)
        assertThat(result.metrics.settleSampleIndex).isEqualTo(0)
    }

    @Test
    fun medianCascadeKeepsTheTWaveThatASingleMidLengthMedianRemoves() {
        val srHz = 500.0
        val values = DoubleArray(15_000) { syntheticEcgAt(it / srHz, 75.0) }

        val cascade = DoubleArray(values.size).also { out ->
            val baseline = EcgSignalChain.baseline(values, srHz)
            for (i in values.indices) out[i] = values[i] - baseline[i]
        }
        val singleStage = DoubleArray(values.size).also { out ->
            val baseline = EcgSignalChain.runningMedian(values, 201)
            for (i in values.indices) out[i] = values[i] - baseline[i]
        }

        // T peaks 230 ms after each R; sample the third beat well clear of the edges.
        val rIndex = (3 * srHz * 60.0 / 75.0).roundToInt()
        val tIndex = rIndex + (0.23 * srHz).roundToInt()
        assertThat(cascade[tIndex]).isGreaterThan(singleStage[tIndex] * 1.08)
        assertThat(cascade[tIndex]).isWithin(0.03).of(T_AMPLITUDE_MV)
    }

    @Test
    fun diagnosticBandwidthKeepsRAmplitudeThatMonitorBandwidthLoses() {
        val srHz = 500.0
        val samples = batchedSamples(count = 15_000, srHz = srHz) { syntheticEcgAt(it, 75.0) }

        val diagnostic = EcgSignalChain.process(samples, 500, 1f, EcgBandwidth.DIAGNOSTIC).valuesMv
        val monitor = EcgSignalChain.process(samples, 500, 1f, EcgBandwidth.MONITOR).valuesMv

        val rIndex = (3 * srHz * 60.0 / 75.0).roundToInt()
        val window = (rIndex - 40)..(rIndex + 40)
        val unfilteredR = window.maxOf { syntheticEcgAt(it / srHz, 75.0) }
        val diagnosticR = window.maxOf { diagnostic[it] }
        val monitorR = window.maxOf { monitor[it] }

        // 150 Hz keeps essentially all of the R wave; 40 Hz measurably clips it,
        // which is why measurements must never be taken off the display trace.
        assertThat(diagnosticR / unfilteredR).isGreaterThan(0.90)
        assertThat(monitorR / diagnosticR).isLessThan(0.92)
    }

    @Test
    fun polarityInvertsTheOutputExactly() {
        val srHz = 500.0
        val samples = batchedSamples(count = 6_000, srHz = srHz) { t ->
            syntheticEcgAt(t, 75.0) + 0.2 * sin(2 * PI * 50.0 * t)
        }

        val upright = EcgSignalChain.process(samples, 500, 1f, EcgBandwidth.DIAGNOSTIC).valuesMv
        val inverted = EcgSignalChain.process(samples, 500, -1f, EcgBandwidth.DIAGNOSTIC).valuesMv

        assertThat(upright.indices.maxOf { abs(upright[it] + inverted[it]) }).isLessThan(1e-9)
    }

    @Test
    fun runningMedianMatchesABruteForceMedian() {
        val random = java.util.Random(7)
        val values = DoubleArray(2_000) { random.nextGaussian() }

        for (kernel in intArrayOf(3, 21, 101, 301)) {
            val fast = EcgSignalChain.runningMedian(values, kernel)
            val slow = bruteForceMedian(values, kernel)
            assertThat(fast.indices.maxOf { abs(fast[it] - slow[it]) }).isLessThan(1e-12)
        }
    }

    @Test
    fun theArrayOverloadFitsTheSameRateAsTheSampleListOverload() {
        val srHz = 501.67
        val samples = batchedSamples(count = 5_000, srHz = srHz) { syntheticEcgAt(it, bpm = 60.0) }
        val fromSamples = EcgSignalChain.estimateSampleRateHz(samples, nominalSrHz = 500)
        val fromArray = EcgSignalChain.estimateSampleRateHz(
            LongArray(samples.size) { samples[it].sensorTimestampMsRaw!! },
            nominalSrHz = 500,
        )
        assertThat(fromArray).isWithin(0.05).of(srHz)
        assertThat(fromArray).isWithin(1e-9).of(fromSamples)
    }

    @Test
    fun theSparseOverloadFitsTheRateFromOneObservationPerBatch() {
        val srHz = 501.67
        val batchSize = 10
        val batches = 400
        val indices = LongArray(batches) { (it * batchSize).toLong() }
        val stamps = LongArray(batches) { 1_000L + (it * batchSize * 1_000.0 / srHz).toLong() }
        val measured = EcgSignalChain.estimateSampleRateHz(indices, stamps, batches, nominalSrHz = 500)
        assertThat(measured).isWithin(0.2).of(srHz)
    }

    @Test
    fun theSparseOverloadFallsBackToNominalWithTooFewObservations() {
        val indices = LongArray(8) { (it * 10).toLong() }
        val stamps = LongArray(8) { 1_000L + it * 20L }
        assertThat(EcgSignalChain.estimateSampleRateHz(indices, stamps, 8, nominalSrHz = 500))
            .isWithin(1e-9).of(500.0)
    }

    @Test
    fun beatAnalyzerUsesTheMeasuredRateForRrIntervals() {
        // 500 declared, 501.6659 measured: the analysis grid must follow the
        // measured rate or every RR interval is 0.33% long.
        assertThat(EcgBeatAnalyzer.analysisSrHz(500, 501.6659)).isWithin(1e-9).of(501.6659)
        assertThat(EcgBeatAnalyzer.analysisSrHz(250, 250.0)).isWithin(1e-9).of(500.0)
        // Implausible rates fall back to the nominal analysis grid.
        assertThat(EcgBeatAnalyzer.analysisSrHz(500, 900.0)).isWithin(1e-9).of(500.0)
        assertThat(EcgBeatAnalyzer.analysisSrHz(500, 0.0)).isWithin(1e-9).of(500.0)
    }

    // ------------------------------------------------------------- fixtures

    private companion object {
        const val R_AMPLITUDE_MV = 0.62
        const val T_AMPLITUDE_MV = 0.38
    }

    /**
     * A Galaxy-Watch-shaped beat: narrow R, deep S 25 ms later, broad T at
     * 230 ms, small P 150 ms ahead. Amplitudes match the median beat measured on
     * real `ECG_ON_DEMAND` captures.
     */
    private fun syntheticEcgAt(timeSec: Double, bpm: Double): Double {
        val rrSec = 60.0 / bpm
        var phase = timeSec % rrSec
        if (phase < 0) phase += rrSec
        val fromR = phase
        val beforeNextR = phase - rrSec
        var value = 0.0
        value += R_AMPLITUDE_MV * gaussian(fromR, 0.0, 0.011)
        value -= 0.47 * gaussian(fromR, 0.025, 0.018)
        value += T_AMPLITUDE_MV * gaussian(fromR, 0.230, 0.048)
        value += 0.06 * gaussian(beforeNextR, -0.150, 0.022)
        return value
    }

    /** Same beat shape with respiratory RR variation, so its harmonics smear. */
    private fun jitteredEcgAt(timeSec: Double): Double {
        val instantaneousBpm = 75.0 + 5.0 * sin(2 * PI * 0.25 * timeSec)
        return syntheticEcgAt(timeSec, instantaneousBpm)
    }

    private fun gaussian(x: Double, centre: Double, width: Double): Double {
        val z = (x - centre) / width
        return exp(-0.5 * z * z)
    }

    /**
     * Samples delivered the way Samsung does: batches of ten sharing one
     * timestamp, so only every tenth stamp is an independent clock observation.
     */
    private fun batchedSamples(count: Int, srHz: Double, valueAt: (Double) -> Double): List<EcgSample> {
        val start = 1_788_169_259_699L
        return List(count) { index ->
            val batch = index / 10
            val stamp = start + (batch * 10 * 1_000.0 / srHz).roundToInt()
            EcgSample(
                relMs = index * 2L,
                valueMv = valueAt(index / srHz).toFloat(),
                hrBpm = null,
                sampleIndex = index,
                sensorTimestampMsRaw = stamp,
                batchSequence = batch % 256,
                batchSampleOffset = index % 10,
                batchSize = 10,
            )
        }
    }

    private fun toneAmplitude(values: DoubleArray, srHz: Double, frequencyHz: Double): Double {
        var sine = 0.0
        var cosine = 0.0
        for (index in values.indices) {
            val angle = 2 * PI * frequencyHz * index / srHz
            sine += values[index] * sin(angle)
            cosine += values[index] * cos(angle)
        }
        return 2.0 * sqrt(sine * sine + cosine * cosine) / values.size
    }

    private fun bruteForceMedian(values: DoubleArray, kernel: Int): DoubleArray {
        val radius = kernel / 2
        val out = DoubleArray(values.size)
        val buffer = DoubleArray(kernel)
        for (i in values.indices) {
            for (k in -radius..radius) {
                buffer[k + radius] = values[(i + k).coerceIn(0, values.size - 1)]
            }
            buffer.sort()
            out[i] = buffer[radius]
        }
        return out
    }

    @Suppress("unused")
    private fun decibels(ratio: Double): Double = 20.0 * log10(ratio)
}

package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.domain.EcgSampleFlags
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertNotSame
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class EcgDisplayProcessorTest {

    @Test
    fun displayFilterReturnsCopiedSamplesWithoutMutatingRawRecording() {
        val samples = List(2_000) { index ->
            EcgSample(
                relMs = index * 2L,
                valueMv = (0.4 * sin(2 * PI * index / 500.0)).toFloat(),
                hrBpm = 60 + index % 3,
                sampleIndex = index,
                flags = if (index == 500) EcgSampleFlags.CONTACT_LOSS else 0,
            )
        }
        val snapshot = samples.map { it.copy() }

        val filtered = EcgDisplayProcessor.filter(
            samples = samples,
            srHz = 500,
            signFactor = 1,
            polarityNormalized = false,
        )

        assertNotSame(samples, filtered)
        assertThat(samples).isEqualTo(snapshot)
        assertThat(filtered).hasSize(samples.size)
        filtered.indices.forEach { index ->
            assertNotSame(samples[index], filtered[index])
            assertThat(filtered[index].relMs).isEqualTo(samples[index].relMs)
            assertThat(filtered[index].hrBpm).isEqualTo(samples[index].hrBpm)
            assertThat(filtered[index].sampleIndex).isEqualTo(samples[index].sampleIndex)
            assertThat(filtered[index].flags).isEqualTo(samples[index].flags)
        }
    }

    @Test
    fun displayFilterRemovesDriftAndMainsWhileKeepingTheRWave() {
        // Probes are ECG-shaped on purpose: the baseline estimator is a median
        // cascade tuned for QRS/T morphology, so a sine tells you nothing useful
        // about what it does to a real recording.
        val srHz = 500
        val count = 30 * srHz
        val samples = List(count) { index ->
            val timeSec = index.toDouble() / srHz
            val drift = 1.2 * index / (count - 1).toDouble()
            val value = drift +
                syntheticBeatAt(timeSec) +
                0.26 * sin(2 * PI * 50.0 * timeSec) +
                0.35 * sin(2 * PI * 80.0 * timeSec)
            EcgSample(index * 1000L / srHz, value.toFloat(), 70, index)
        }

        val filtered = EcgDisplayProcessor.filter(samples, srHz, 1, false)
        val rawValues = FloatArray(count) { samples[it].valueMv }
        val filteredValues = FloatArray(count) { filtered[it].valueMv }
        val skip = 5 * srHz

        assertThat(abs(linearSlope(filteredValues, skip)))
            .isLessThan(abs(linearSlope(rawValues, skip)) * 0.2)
        assertThat(toneAmplitude(filteredValues, srHz, 80.0, skip))
            .isLessThan(toneAmplitude(rawValues, srHz, 80.0, skip) * 0.1)
        // Samsung delivers unfiltered ECG; without a notch the 50 Hz fundamental
        // stays the size of a P wave even after a 40 Hz low-pass.
        assertThat(toneAmplitude(filteredValues, srHz, 50.0, skip))
            .isLessThan(toneAmplitude(rawValues, srHz, 50.0, skip) * 0.05)
        val beatWindow = (10 * srHz) until (11 * srHz)
        val rawR = beatWindow.maxOf { syntheticBeatAt(it.toDouble() / srHz) }
        assertThat(beatWindow.maxOf { filteredValues[it] }.toDouble()).isGreaterThan(rawR * 0.8)
    }

    @Test
    fun displayFilterAppliesEffectivePolarityExactlyOnce() {
        val samples = List(2_000) { index ->
            val timeSec = index / 500.0
            EcgSample(index * 2L, sin(2 * PI * 1.3 * timeSec).toFloat(), 70, index)
        }

        val left = EcgDisplayProcessor.filter(samples, 500, 1, false)
        val normalizedRight = EcgDisplayProcessor.filter(samples, 500, -1, true)
        val rawRight = EcgDisplayProcessor.filter(samples, 500, -1, false)

        assertThat(maxAbsoluteDifference(left, normalizedRight)).isLessThan(1e-6f)
        assertThat(maxAbsoluteSum(left, rawRight)).isLessThan(1e-6f)
    }

    @Test
    fun constantOffsetDoesNotCreateAStartupSpike() {
        val samples = List(500) { index -> EcgSample(index * 2L, 2f, null, index) }

        val filtered = EcgDisplayProcessor.filter(samples, 500, 1, false)

        assertThat(filtered.maxOf { abs(it.valueMv) }).isLessThan(1e-6f)
    }

    @Test
    fun watchElectrodeOffsetAndSlowDriftDoNotDominateTheFirstTwoSeconds() {
        val srHz = 500
        val count = 30 * srHz
        val samples = List(count) { index ->
            val timeSec = index.toDouble() / srHz
            val electrodeOffset = 140.0 + 16.0 * kotlin.math.exp(-timeSec / 1.5)
            EcgSample(index * 2L, (electrodeOffset + syntheticBeatAt(timeSec)).toFloat(), 75, index)
        }

        val filtered = EcgDisplayProcessor.filter(samples, srHz, 1, false)
        val firstTwo = peakToPeak(filtered, 0, 2 * srHz)
        val middle = peakToPeak(filtered, 10 * srHz, 20 * srHz)

        assertThat(middle.toDouble()).isGreaterThan(0.6)
        // The median-cascade baseline absorbs the polarization step instead of
        // ringing on it, so the head of the record is the same size as the rest.
        assertThat(firstTwo.toDouble()).isLessThan(middle * 1.25)
    }

    /** Narrow R, deep S, broad T at 75 bpm - the shape a Galaxy Watch records. */
    private fun syntheticBeatAt(timeSec: Double): Double {
        val rrSec = 60.0 / 75.0
        var phase = timeSec % rrSec
        if (phase < 0) phase += rrSec
        fun bump(centre: Double, width: Double): Double {
            val z = (phase - centre) / width
            return kotlin.math.exp(-0.5 * z * z)
        }
        return 0.62 * bump(0.0, 0.016) - 0.47 * bump(0.030, 0.020) + 0.38 * bump(0.230, 0.048)
    }

    private fun linearSlope(values: FloatArray, start: Int): Double {
        val count = values.size - start
        val meanX = (start + values.lastIndex) / 2.0
        val meanY = (start until values.size).sumOf { values[it].toDouble() } / count
        var numerator = 0.0
        var denominator = 0.0
        for (index in start until values.size) {
            val centeredX = index - meanX
            numerator += centeredX * (values[index] - meanY)
            denominator += centeredX * centeredX
        }
        return numerator / denominator
    }

    private fun toneAmplitude(
        values: FloatArray,
        srHz: Int,
        frequencyHz: Double,
        start: Int,
    ): Double {
        var sine = 0.0
        var cosine = 0.0
        for (index in start until values.size) {
            val angle = 2 * PI * frequencyHz * index / srHz
            sine += values[index] * sin(angle)
            cosine += values[index] * cos(angle)
        }
        val count = values.size - start
        return 2.0 * kotlin.math.sqrt(sine * sine + cosine * cosine) / count
    }

    private fun peakToPeak(samples: List<EcgSample>, start: Int, endExclusive: Int): Float {
        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        for (index in start until endExclusive) {
            val value = samples[index].valueMv
            if (value < minV) minV = value
            if (value > maxV) maxV = value
        }
        return maxV - minV
    }

    private fun maxAbsoluteDifference(first: List<EcgSample>, second: List<EcgSample>): Float =
        first.indices.maxOf { abs(first[it].valueMv - second[it].valueMv) }

    private fun maxAbsoluteSum(first: List<EcgSample>, second: List<EcgSample>): Float =
        first.indices.maxOf { abs(first[it].valueMv + second[it].valueMv) }
}

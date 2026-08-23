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
    fun displayFilterAttenuatesSlowRampAnd80HzNoiseWhileKeepingOneHzWave() {
        val srHz = 500
        val seconds = 30
        val count = seconds * srHz
        val samples = List(count) { index ->
            val timeSec = index.toDouble() / srHz
            val ramp = 1.2 * index / (count - 1).toDouble()
            val value = ramp +
                0.7 * sin(2 * PI * timeSec) +
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
            .isLessThan(toneAmplitude(rawValues, srHz, 80.0, skip) * 0.55)
        assertThat(toneAmplitude(filteredValues, srHz, 1.0, skip))
            .isGreaterThan(toneAmplitude(rawValues, srHz, 1.0, skip) * 0.75)
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

    private fun maxAbsoluteDifference(first: List<EcgSample>, second: List<EcgSample>): Float =
        first.indices.maxOf { abs(first[it].valueMv - second[it].valueMv) }

    private fun maxAbsoluteSum(first: List<EcgSample>, second: List<EcgSample>): Float =
        first.indices.maxOf { abs(first[it].valueMv + second[it].valueMv) }
}

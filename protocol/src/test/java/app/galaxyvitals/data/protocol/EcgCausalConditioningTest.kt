package app.galaxyvitals.data.protocol

import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Test

class EcgCausalConditioningTest {
    @Test
    fun streamingBaselineMatchesTheOfflineMedianCascade() {
        val srHz = 500.0
        val count = 5_000
        val values = DoubleArray(count) { index ->
            val t = index / srHz
            // A wrist trace: electrode offset, slow wander, beats on top.
            120.0 + 3.0 * sin(2 * PI * 0.25 * t) + beatAt(t)
        }
        val offline = EcgSignalChain.baseline(values, srHz)

        val streaming = DelayedMedianBaseline(srHz)
        val drawn = ArrayList<Float>(count)
        for (value in values) streaming.push(value.toFloat())?.let { drawn += it }

        assertThat(streaming.lookaheadSamples).isEqualTo(200)
        assertThat(drawn).hasSize(count - streaming.lookaheadSamples)
        // The offline pass pads the tail with the last sample; a live stream has
        // no tail to pad, so the two only see the same window away from the end.
        for (index in 0 until count - 2 * streaming.lookaheadSamples) {
            assertThat(drawn[index].toDouble())
                .isWithin(1e-3)
                .of(values[index] - offline[index])
        }
    }

    @Test
    fun electrodePolarisationStepIsAbsorbedInsteadOfRinging() {
        val srHz = 500.0
        val count = 3_000
        // 17 mV over 250 ms, which is what the watch AFE does when a capture
        // listener starts, under a QRS about a millivolt tall.
        val values = FloatArray(count) { index ->
            val polarisation = if (index >= 125) 58.0 else 41.0 + 17.0 * index / 125.0
            (polarisation + beatAt(index / srHz)).toFloat()
        }

        val baseline = DelayedMedianBaseline(srHz)
        val lowPass = CausalSosFilter(EcgCausalConditioning.LOWPASS_SOS_500)
        val drawn = ArrayList<Float>(count)
        for (value in values) baseline.push(value)?.let { drawn += lowPass.filter(it) }

        val steadyPeak = drawn.drop(1_000).maxOf { abs(it) }
        val head = drawn.take(500).drop(LiveDisplayWarmupSamples).maxOf { abs(it) }
        assertThat(head).isLessThan(1.5f * steadyPeak)
    }

    @Test
    fun lowPassKeepsTheQrsBandAndCutsMains() {
        val srHz = 500.0
        val count = 4_000
        fun amplitudeAt(frequencyHz: Double): Double {
            val filter = CausalSosFilter(EcgCausalConditioning.LOWPASS_SOS_500)
            val out = DoubleArray(count) { filter.filter(sin(2 * PI * frequencyHz * it / srHz)) }
            // Skip the settling head, then measure the steady-state swing.
            val tail = out.drop(1_000)
            return (tail.max() - tail.min()) / 2.0
        }

        assertThat(amplitudeAt(10.0)).isWithin(0.02).of(1.0)
        assertThat(amplitudeAt(40.0)).isWithin(0.02).of(0.7071)
        assertThat(amplitudeAt(50.0)).isLessThan(0.4)
    }

    private fun beatAt(timeSec: Double): Double {
        val rrSec = 60.0 / 75.0
        var phase = timeSec % rrSec
        if (phase < 0) phase += rrSec
        fun bump(centre: Double, width: Double): Double {
            val z = (phase - centre) / width
            return exp(-0.5 * z * z)
        }
        return 0.62 * bump(0.0, 0.016) - 0.47 * bump(0.030, 0.020) + 0.38 * bump(0.230, 0.048)
    }

    private companion object {
        /** Mirrors `LiveEcgProcessor.DISPLAY_WARMUP_SAMPLES`, which wear owns. */
        const val LiveDisplayWarmupSamples = 100
    }
}

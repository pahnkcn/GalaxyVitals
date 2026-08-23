package app.galaxyvitals.wear.ui

import app.galaxyvitals.data.protocol.EcgWearContract
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.exp
import org.junit.Test

class LiveBpmEstimatorTest {
    @Test
    fun estimatesBpmFromPpgGreenPulse() {
        val ppg = syntheticPpg(seconds = 3, bpm = 72)
        val bpm = LiveBpmEstimator.estimateBpm(samples = List(ppg.size) { 0f }, ppgGreen = ppg.toList())
        assertThat(bpm).isNotNull()
        assertThat(abs(bpm!! - 72)).isAtMost(4)
    }

    @Test
    fun prefersPpgOverEcgWhenBothPresent() {
        val ppg = syntheticPpg(seconds = 3, bpm = 64)
        val ecg = syntheticQrs(seconds = 3, bpm = 110)
        val bpm = LiveBpmEstimator.estimateBpm(samples = ecg.toList(), ppgGreen = ppg.toList())
        assertThat(bpm).isNotNull()
        assertThat(abs(bpm!! - 64)).isAtMost(6)
    }

    @Test
    fun estimatesBpmFromSyntheticQrsTrain() {
        val samples = syntheticQrs(seconds = 3, bpm = 72)
        val bpm = LiveBpmEstimator.estimateBpm(samples.toList())
        assertThat(bpm).isNotNull()
        assertThat(abs(bpm!! - 72)).isAtMost(4)
    }

    @Test
    fun estimatesFasterRhythm() {
        val samples = syntheticQrs(seconds = 3, bpm = 110)
        val bpm = LiveBpmEstimator.estimateBpm(samples.toList())
        assertThat(bpm).isNotNull()
        assertThat(abs(bpm!! - 110)).isAtMost(6)
    }

    @Test
    fun returnsNullForFlatline() {
        assertThat(LiveBpmEstimator.estimateBpm(List(1_500) { 0.02f })).isNull()
    }

    @Test
    fun returnsNullWhenBufferIsTooShort() {
        val samples = syntheticQrs(seconds = 3, bpm = 72).take(200)
        assertThat(LiveBpmEstimator.estimateBpm(samples)).isNull()
    }

    @Test
    fun ignoresTWavesAndReportsTrueRate() {
        val samples = syntheticQrsWithTWave(seconds = 3, bpm = 72)
        val bpm = LiveBpmEstimator.estimateBpm(samples.toList())
        assertThat(bpm).isNotNull()
        assertThat(abs(bpm!! - 72)).isAtMost(6)
    }

    @Test
    fun missedBeatDoesNotHalveRate() {
        val samples = syntheticQrs(seconds = 5, bpm = 80)
        val period = EcgWearContract.DEFAULT_SR_HZ * 60 / 80
        val droppedPeak = period / 2 + 2 * period
        for (i in (droppedPeak - 8)..(droppedPeak + 8)) {
            if (i in samples.indices) samples[i] = 0f
        }
        val bpm = LiveBpmEstimator.estimateBpm(samples.toList())
        if (bpm != null) {
            assertThat(abs(bpm - 80)).isAtMost(8)
        }
    }

    @Test
    fun tallArtifactDoesNotCollapseRate() {
        val samples = syntheticQrs(seconds = 3, bpm = 72)
        samples[20] = 6f
        val bpm = LiveBpmEstimator.estimateBpm(samples.toList())
        assertThat(bpm).isNotNull()
        assertThat(abs(bpm!! - 72)).isAtMost(6)
    }

    private fun syntheticQrsWithTWave(
        seconds: Int,
        bpm: Int,
        srHz: Int = EcgWearContract.DEFAULT_SR_HZ,
    ): FloatArray {
        val out = syntheticQrs(seconds, bpm, srHz)
        val period = srHz * 60 / bpm
        val tOffset = srHz * 420 / 1_000
        var peak = period / 2
        while (peak < out.size) {
            val tPeak = peak + tOffset
            for (offset in -8..8) {
                val index = tPeak + offset
                if (index in out.indices) {
                    out[index] += (0.75 * exp(-offset * offset / 12.0)).toFloat()
                }
            }
            peak += period
        }
        return out
    }

    private fun syntheticPpg(seconds: Int, bpm: Int, srHz: Int = EcgWearContract.DEFAULT_SR_HZ): IntArray {
        val n = seconds * srHz
        val period = srHz * 60 / bpm
        val out = IntArray(n)
        val peak = period / 5
        val sigma = period * 0.08
        for (index in 0 until n) {
            val t = index % period
            val gauss = exp(-((t - peak) * (t - peak)) / (2.0 * sigma * sigma))
            out[index] = (12_000 + 4_000 * gauss).toInt()
        }
        return out
    }

    private fun syntheticQrs(seconds: Int, bpm: Int, srHz: Int = EcgWearContract.DEFAULT_SR_HZ): FloatArray {
        val n = seconds * srHz
        val period = srHz * 60 / bpm
        val out = FloatArray(n)
        var peak = period / 2
        while (peak < n) {
            for (offset in -5..5) {
                val index = peak + offset
                if (index in 0 until n) {
                    out[index] = (1.5 * exp(-offset * offset / 6.0)).toFloat()
                }
            }
            peak += period
        }
        return out
    }
}

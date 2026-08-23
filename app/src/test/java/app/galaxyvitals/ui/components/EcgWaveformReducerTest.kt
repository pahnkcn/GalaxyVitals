package app.galaxyvitals.ui.components

import app.galaxyvitals.domain.EcgSample
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EcgWaveformReducerTest {
    @Test
    fun preservesNarrowQrsPeakWithinCanvasBound() {
        val peakIndex = 7_421
        val samples = List(15_000) { index ->
            EcgSample(
                relMs = index * 2L,
                valueMv = if (index == peakIndex) 4.5f else 0f,
                hrBpm = null,
                sampleIndex = index,
            )
        }

        val reduced = reduceWaveform(samples, maxPoints = 320)

        assertThat(reduced.size).isAtMost(640)
        assertThat(reduced).contains(samples[peakIndex])
    }

    @Test
    fun emitsBucketExtremaInChronologicalOrder() {
        val samples = listOf(
            sample(index = 0, value = 0f),
            sample(index = 1, value = 5f),
            sample(index = 2, value = 1f),
            sample(index = 3, value = -4f),
        )

        val reduced = reduceWaveform(samples, maxPoints = 1)

        assertThat(reduced.map { it.sampleIndex }).containsExactly(1, 3).inOrder()
    }

    private fun sample(index: Int, value: Float) = EcgSample(
        relMs = index * 2L,
        valueMv = value,
        hrBpm = null,
        sampleIndex = index,
    )
}

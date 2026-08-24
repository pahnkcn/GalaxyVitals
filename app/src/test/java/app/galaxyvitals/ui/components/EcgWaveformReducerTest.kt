package app.galaxyvitals.ui.components

import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.domain.EcgSampleFlags
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

        val reduced = reduceWaveform(samples, physicalPixelWidth = 320)

        assertThat(reduced.size).isAtMost(640)
        val spike = reduced.single { it.valueMv == 4.5f }
        assertThat(spike.sampleIndex).isEqualTo(peakIndex.toLong())
        assertThat(spike.sampleIndex).isNotEqualTo(reduced.indexOf(spike).toLong())
    }

    @Test
    fun emitsBucketExtremaInChronologicalOrder() {
        val samples = listOf(
            sample(index = 0, value = 0f),
            sample(index = 1, value = 5f),
            sample(index = 2, value = 1f),
            sample(index = 3, value = -4f),
        )

        val reduced = reduceWaveform(samples, physicalPixelWidth = 2)

        assertThat(reduced.map { it.sampleIndex }).containsExactly(0L, 1L, 3L).inOrder()
        assertThat(reduced.map { it.valueMv }).containsExactly(0f, 5f, -4f).inOrder()
    }

    @Test
    fun mapsXFromSampleIndexAndKeepsGapSegments() {
        val samples = listOf(
            sample(index = 0, value = 0.2f),
            sample(index = 1, value = 0.3f),
            sample(index = 80, value = -0.2f, flags = EcgSampleFlags.TIMESTAMP_GAP),
            sample(index = 81, value = -0.1f),
        )

        val points = toWaveformPoints(samples)
        val reduced = reduceWaveform(samples, physicalPixelWidth = 8)

        assertThat(points[2].startsNewSegment).isTrue()
        assertThat(reduced.first { it.sampleIndex == 80L }.startsNewSegment).isTrue()
        assertThat(reduced.map { it.sampleIndex }).containsAtLeast(0L, 80L).inOrder()
    }

    @Test
    fun fullTraceScaleIgnoresLeadingElectrodeSwing() {
        val samples = List(15_000) { index ->
            val value = if (index < 1_000) {
                if (index == 10) 8f else 0.1f
            } else {
                if (index % 400 == 0) 0.8f else 0f
            }
            EcgSample(index * 2L, value, null, index)
        }

        val bounds = waveformAmplitudeBounds(samples, viewingFromStart = true)

        assertThat(bounds.span).isLessThan(2f)
        assertThat(bounds.span).isGreaterThan(0.5f)
    }

    private fun sample(index: Int, value: Float, flags: Int = EcgSampleFlags.NONE) = EcgSample(
        relMs = index * 2L,
        valueMv = value,
        hrBpm = null,
        sampleIndex = index,
        flags = flags,
    )
}

package app.galaxyvitals.data.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.exp

class EcgWaveformGeometryTest {

    @Test
    fun spikeSurvivesM4WithOriginalSampleIndex() {
        val peakIndex = 7_421L
        val points = List(15_000) { index ->
            WaveformPoint(
                sampleIndex = index.toLong(),
                valueMv = if (index.toLong() == peakIndex) 4.5f else 0f,
            )
        }

        val reduced = EcgWaveformGeometry.reduceM4(points, physicalPixelWidth = 320)

        val spike = reduced.single { it.valueMv == 4.5f }
        assertThat(spike.sampleIndex).isEqualTo(peakIndex)
        assertThat(spike.sampleIndex).isNotEqualTo(reduced.indexOf(spike).toLong())
        assertThat(reduced.size).isAtMost(640)
    }

    @Test
    fun reduceM4EmitsFirstMinMaxLastInChronologicalOrder() {
        val points = listOf(
            WaveformPoint(sampleIndex = 0, valueMv = 0f),
            WaveformPoint(sampleIndex = 1, valueMv = 5f),
            WaveformPoint(sampleIndex = 2, valueMv = 1f),
            WaveformPoint(sampleIndex = 3, valueMv = -4f),
        )

        val reduced = EcgWaveformGeometry.reduceM4(points, physicalPixelWidth = 2)

        assertThat(reduced.map { it.sampleIndex }).containsExactly(0L, 1L, 3L).inOrder()
        assertThat(reduced.map { it.valueMv }).containsExactly(0f, 5f, -4f).inOrder()
    }

    @Test
    fun reduceM4DoesNotConnectAcrossStartsNewSegment() {
        val left = List(40) { index ->
            WaveformPoint(sampleIndex = index.toLong(), valueMv = 0.4f)
        }
        val right = List(40) { index ->
            WaveformPoint(
                sampleIndex = 8_000L + index,
                valueMv = -0.4f,
                startsNewSegment = index == 0,
            )
        }

        val reduced = EcgWaveformGeometry.reduceM4(left + right, physicalPixelWidth = 16)
        val firstRight = reduced.first { it.sampleIndex >= 8_000L }
        val lastLeft = reduced.last { it.sampleIndex < 8_000L }

        assertThat(reduced.first().startsNewSegment).isTrue()
        assertThat(firstRight.startsNewSegment).isTrue()
        assertThat(reduced.count { it.startsNewSegment }).isEqualTo(2)
        assertThat(lastLeft.sampleIndex).isAtMost(39L)
        assertThat(firstRight.sampleIndex).isAtLeast(8_000L)
        assertThat(reduced.none { it.sampleIndex in 40L until 8_000L }).isTrue()
    }

    @Test
    fun singleOutlierDoesNotCollapseScale() {
        val bulk = List(1_000) { index ->
            WaveformPoint(
                sampleIndex = index.toLong(),
                valueMv = if (index % 50 == 0) 0.8f else 0.05f,
            )
        }
        val withOutlier = bulk.mapIndexed { index, point ->
            if (index == 10) point.copy(valueMv = 20f) else point
        }
        val previous = WaveformScale(centerMv = 0f, halfRangeMv = 1.0f)

        val without = EcgWaveformGeometry.nextScale(bulk, previous, deltaMs = 10_000L)
        val with = EcgWaveformGeometry.nextScale(withOutlier, previous, deltaMs = 10_000L)

        assertThat(with.halfRangeMv).isWithin(0.15f).of(without.halfRangeMv)
        assertThat(with.halfRangeMv).isAtLeast(0.5f)
        assertThat(with.halfRangeMv).isAtMost(5.0f)
        assertThat(with.halfRangeMv).isLessThan(4.0f)
    }

    @Test
    fun nextScaleExpandsHalfRangeImmediatelyAndShrinksWithFiveSecondTau() {
        val wide = List(200) { index ->
            WaveformPoint(sampleIndex = index.toLong(), valueMv = if (index % 8 == 0) 2.0f else 0.1f)
        }
        val quiet = List(200) { index ->
            WaveformPoint(sampleIndex = index.toLong(), valueMv = 0.02f)
        }
        val previous = WaveformScale.Default

        val expanded = EcgWaveformGeometry.nextScale(wide, previous, deltaMs = 50L)
        assertThat(expanded.halfRangeMv).isGreaterThan(previous.halfRangeMv)

        val shrinking = WaveformScale(centerMv = 0f, halfRangeMv = 3.0f)
        val after50Ms = EcgWaveformGeometry.nextScale(quiet, shrinking, deltaMs = 50L)
        val alpha50 = (1.0 - exp(-50.0 / 5_000.0)).toFloat()
        assertThat(after50Ms.halfRangeMv)
            .isWithin(1e-4f)
            .of(3.0f + alpha50 * (0.5f - 3.0f))

        val shifted = List(200) { index ->
            WaveformPoint(sampleIndex = index.toLong(), valueMv = 1.0f)
        }
        val afterCenter = EcgWaveformGeometry.nextScale(shifted, previous, deltaMs = 50L)
        assertThat(afterCenter.centerMv).isWithin(1e-4f).of(alpha50 * 1.0f)
        assertThat(afterCenter.centerMv).isLessThan(0.05f)
    }
}

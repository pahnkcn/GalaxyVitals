package app.galaxyvitals.data.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EcgStripLayoutTest {

    private val spec = StripSpec()

    @Test
    fun tenSecondsAtStandardSpeedIsExactlyTwoHundredFiftyMillimetres() {
        val row = EcgStripLayout.rows(durationSec = 30.0, spec = spec).first()

        val start = EcgStripLayout.xMm(0.0, row, spec)
        val end = EcgStripLayout.xMm(10.0, row, spec)

        assertThat(end - start).isWithin(1e-9).of(250.0)
        assertThat(EcgStripLayout.widthMm(spec)).isWithin(1e-9).of(260.0)
    }

    @Test
    fun oneMillivoltAtStandardGainIsExactlyTenMillimetres() {
        val row = EcgStripLayout.rows(durationSec = 30.0, spec = spec).first()

        val baseline = EcgStripLayout.yMm(0.0, row, spec)
        val oneMv = EcgStripLayout.yMm(1.0, row, spec)

        assertThat(baseline - oneMv).isWithin(1e-9).of(10.0)
    }

    @Test
    fun oneLargeBoxIsTwoHundredMillisecondsAndHalfAMillivolt() {
        val row = EcgStripLayout.rows(durationSec = 30.0, spec = spec).first()

        val boxWidth = EcgStripLayout.xMm(0.2, row, spec) - EcgStripLayout.xMm(0.0, row, spec)
        val boxHeight = EcgStripLayout.yMm(0.0, row, spec) - EcgStripLayout.yMm(0.5, row, spec)

        assertThat(boxWidth).isWithin(1e-9).of(StripSpec.MAJOR_GRID_MM)
        assertThat(boxHeight).isWithin(1e-9).of(StripSpec.MAJOR_GRID_MM)
    }

    @Test
    fun thirtySecondsSplitsIntoThreeTenSecondRows() {
        val rows = EcgStripLayout.rows(durationSec = 30.0, spec = spec)

        assertThat(rows).hasSize(3)
        assertThat(rows.map { it.startSec }).containsExactly(0.0, 10.0, 20.0).inOrder()
        assertThat(rows.map { it.topMm }).containsExactly(0.0, 40.0, 80.0).inOrder()
        assertThat(rows.map { it.baselineMm }).containsExactly(20.0, 60.0, 100.0).inOrder()
        assertThat(EcgStripLayout.heightMm(rows.size, spec)).isWithin(1e-9).of(120.0)
    }

    @Test
    fun aRecordingShorterThanOneRowStillGetsAFullRow() {
        val rows = EcgStripLayout.rows(durationSec = 3.4, spec = spec)

        assertThat(rows).hasSize(1)
        assertThat(rows.single().endSec).isEqualTo(10.0)
    }

    @Test
    fun calibrationPulseIsOneMillivoltHeldForTwoHundredMilliseconds() {
        val row = EcgStripLayout.rows(durationSec = 30.0, spec = spec).first()

        val pulse = EcgStripLayout.calibrationPulseMm(row, spec)

        assertThat(pulse).hasSize(5)
        val heldWidth = pulse[3].xMm - pulse[2].xMm
        val heldHeight = row.baselineMm - pulse[2].yMm
        assertThat(heldWidth).isWithin(1e-9).of(5.0)
        assertThat(heldHeight).isWithin(1e-9).of(10.0)
        // The pulse ends where the trace begins, so it never overlaps the signal.
        assertThat(pulse.last().xMm).isWithin(1e-9).of(EcgStripLayout.traceStartXMm(spec))
        assertThat(pulse.last().yMm).isWithin(1e-9).of(row.baselineMm)
    }

    @Test
    fun majorGridLinesAreMeasuredFromTheTraceOriginNotTheSheetEdge() {
        val grid = EcgStripLayout.grid(rowCount = 3, spec = spec)

        assertThat(grid.majorXMm.toList()).contains(EcgStripLayout.traceStartXMm(spec))
        grid.majorXMm.forEach { x ->
            val offset = x - EcgStripLayout.traceStartXMm(spec)
            assertThat(offset.mod(StripSpec.MAJOR_GRID_MM)).isWithin(1e-9).of(0.0)
        }
        // Row boundaries and baselines both land on major lines at a 40 mm row.
        assertThat(grid.majorYMm.toList()).containsAtLeast(0.0, 20.0, 40.0, 120.0)
    }

    @Test
    fun gridCoversTheWholeSheetWithoutOverlapBetweenMinorAndMajor() {
        val grid = EcgStripLayout.grid(rowCount = 3, spec = spec)
        val width = EcgStripLayout.widthMm(spec)

        assertThat(grid.minorXMm.size + grid.majorXMm.size).isEqualTo(261)
        assertThat(grid.minorYMm.size + grid.majorYMm.size).isEqualTo(121)
        assertThat(grid.minorXMm.toList().intersect(grid.majorXMm.toList())).isEmpty()
        grid.minorXMm.forEach { assertThat(it).isAtMost(width) }
    }

    @Test
    fun aValueBeyondTheLaneClampsToTheLaneAndIsReportedAsClipped() {
        val row = EcgStripLayout.rows(durationSec = 30.0, spec = spec).first()

        assertThat(EcgStripLayout.yMm(9.0, row, spec)).isWithin(1e-9).of(row.topMm)
        assertThat(EcgStripLayout.yMm(-9.0, row, spec)).isWithin(1e-9).of(row.topMm + spec.rowHeightMm)
        assertThat(EcgStripLayout.isClipped(9.0, spec)).isTrue()
        assertThat(EcgStripLayout.isClipped(1.9, spec)).isFalse()
        assertThat(spec.halfRangeMv).isWithin(1e-9).of(2.0)
    }

    @Test
    fun rowSampleRangeFollowsTheSampleGridNotTimestamps() {
        val rows = EcgStripLayout.rows(durationSec = 30.0, spec = spec)

        val second = EcgStripLayout.rowSampleRange(rows[1], srHz = 500.0, sampleCount = 15_000)

        assertThat(second.first).isEqualTo(5_000)
        assertThat(second.last).isEqualTo(10_000)
        assertThat(EcgStripLayout.rowSampleRange(rows[2], 500.0, 15_000).last).isEqualTo(14_999)
        assertThat(EcgStripLayout.rowSampleRange(rows[0], 500.0, 0)).isEmpty()
    }

    @Test
    fun changingSpeedAndGainRescalesWithoutMovingTheOrigin() {
        val fast = spec.copy(speedMmPerSec = 50.0, gainMmPerMv = 20.0)
        val row = EcgStripLayout.rows(durationSec = 30.0, fast).first()

        assertThat(EcgStripLayout.xMm(0.0, row, fast)).isWithin(1e-9).of(fast.calGutterMm)
        assertThat(EcgStripLayout.xMm(1.0, row, fast) - fast.calGutterMm).isWithin(1e-9).of(50.0)
        assertThat(row.baselineMm - EcgStripLayout.yMm(0.5, row, fast)).isWithin(1e-9).of(10.0)
        assertThat(fast.halfRangeMv).isWithin(1e-9).of(1.0)
    }

    @Test
    fun fitFactorMakesOneSheetExactlyFillTheAvailableWidth() {
        val pxPerMm = EcgStripLayout.pxPerMmToFit(1040f, spec)

        assertThat(pxPerMm).isWithin(1e-6f).of(4f)
        assertThat(EcgStripLayout.rowWidthPx(pxPerMm, spec)).isEqualTo(1040)
        assertThat(EcgStripLayout.pxPerMmToFit(0f, spec)).isEqualTo(0f)
    }

    @Test
    fun pdfPointConversionMatchesSeventyTwoDpi() {
        assertThat(250.0 * EcgStripLayout.PDF_POINTS_PER_MM).isWithin(1e-6).of(708.6614173228347)
        assertThat(25.4 * EcgStripLayout.PDF_POINTS_PER_MM).isWithin(1e-9).of(72.0)
    }
}

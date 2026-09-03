package app.galaxyvitals.data.protocol

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Millimetre geometry for a clinical ECG strip.
 *
 * Every ECG since 1913 is read at 25 mm/s and 10 mm/mV, so a strip is only
 * referenceable if its geometry is expressed in real millimetres rather than in
 * whatever pixels the surface happens to have. This lays the sheet out in the
 * millimetre domain and leaves the mm-to-pixel factor to the renderer, so the
 * Compose canvas and the PDF canvas draw the same sheet from the same numbers.
 *
 * The origin is the top-left of the sheet. `x` grows right, `y` grows down, and
 * a positive millivolt therefore moves *up* the page.
 */
data class StripSpec(
    /** Paper speed. 25 mm/s is the clinical standard; 12.5 and 50 are the accepted alternates. */
    val speedMmPerSec: Double = STANDARD_SPEED_MM_PER_SEC,
    /** Amplitude gain. 10 mm/mV is the clinical standard; 5 and 20 are the accepted alternates. */
    val gainMmPerMv: Double = STANDARD_GAIN_MM_PER_MV,
    /** Seconds carried by one row. 10 s per row is what a three-row 30 s sheet needs. */
    val rowSeconds: Double = 10.0,
    /** Row height. 40 mm at 10 mm/mV puts the clip limit at +/- 2 mV. */
    val rowHeightMm: Double = 40.0,
    /**
     * Width of the left band holding the calibration pulse. A multiple of 5 mm
     * so that t=0 lands on a major grid line.
     */
    val calGutterMm: Double = 10.0,
) {
    init {
        require(speedMmPerSec > 0.0) { "speed must be positive" }
        require(gainMmPerMv > 0.0) { "gain must be positive" }
        require(rowSeconds > 0.0) { "row must span time" }
        require(rowHeightMm > 0.0) { "row must have height" }
        require(calGutterMm >= 0.0) { "gutter cannot be negative" }
    }

    /** Millivolts between the baseline and the top edge of a row. */
    val halfRangeMv: Double get() = rowHeightMm / 2.0 / gainMmPerMv

    companion object {
        const val STANDARD_SPEED_MM_PER_SEC = 25.0
        const val STANDARD_GAIN_MM_PER_MV = 10.0

        /** Height of the calibration pulse. One millivolt, by definition. */
        const val CAL_PULSE_MV = 1.0

        /** Width of the calibration pulse. 200 ms, by convention. */
        const val CAL_PULSE_SEC = 0.2

        const val MINOR_GRID_MM = 1.0
        const val MAJOR_GRID_MM = 5.0

        val SPEED_OPTIONS = listOf(12.5, 25.0, 50.0)
        val GAIN_OPTIONS = listOf(5.0, 10.0, 20.0)
    }
}

/** One horizontal lane of the sheet, carrying `[startSec, endSec)` of the recording. */
data class StripRow(
    val index: Int,
    val startSec: Double,
    val endSec: Double,
    val topMm: Double,
    val baselineMm: Double,
)

/** A point on the sheet, in millimetres from its top-left corner. */
data class PointMm(val xMm: Double, val yMm: Double)

/**
 * Grid line positions in millimetres. Minor lines exclude the major ones, so a
 * renderer can stroke each set once at its own weight without overdraw.
 */
data class StripGrid(
    val minorXMm: DoubleArray,
    val majorXMm: DoubleArray,
    val minorYMm: DoubleArray,
    val majorYMm: DoubleArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StripGrid) return false
        return minorXMm.contentEquals(other.minorXMm) &&
            majorXMm.contentEquals(other.majorXMm) &&
            minorYMm.contentEquals(other.minorYMm) &&
            majorYMm.contentEquals(other.majorYMm)
    }

    override fun hashCode(): Int {
        var result = minorXMm.contentHashCode()
        result = 31 * result + majorXMm.contentHashCode()
        result = 31 * result + minorYMm.contentHashCode()
        result = 31 * result + majorYMm.contentHashCode()
        return result
    }
}

object EcgStripLayout {

    /** Points per millimetre in a PDF, whose user space is 1/72 inch. */
    const val PDF_POINTS_PER_MM = 72.0 / 25.4

    /**
     * Splits a recording into rows. A recording shorter than one row still gets
     * one row, so a truncated capture is drawn against a full-height grid rather
     * than a collapsed one.
     */
    fun rows(durationSec: Double, spec: StripSpec): List<StripRow> {
        val safeDuration = if (durationSec.isFinite()) durationSec.coerceAtLeast(0.0) else 0.0
        val count = ceil(safeDuration / spec.rowSeconds).toInt().coerceAtLeast(1)
        return List(count) { index ->
            val top = index * spec.rowHeightMm
            StripRow(
                index = index,
                startSec = index * spec.rowSeconds,
                endSec = (index + 1) * spec.rowSeconds,
                topMm = top,
                baselineMm = top + spec.rowHeightMm / 2.0,
            )
        }
    }

    /** Sheet width: the calibration gutter plus one row of trace. */
    fun widthMm(spec: StripSpec): Double = spec.calGutterMm + spec.rowSeconds * spec.speedMmPerSec

    fun heightMm(rowCount: Int, spec: StripSpec): Double = rowCount * spec.rowHeightMm

    /** Where the trace for a row begins. Everything left of this is the gutter. */
    fun traceStartXMm(spec: StripSpec): Double = spec.calGutterMm

    fun xMm(tSec: Double, row: StripRow, spec: StripSpec): Double =
        spec.calGutterMm + (tSec - row.startSec) * spec.speedMmPerSec

    /**
     * Maps a millivolt value onto the row, clamped to the row's own bounds so a
     * saturated sample flattens against the lane edge instead of bleeding into
     * the neighbouring one.
     */
    fun yMm(valueMv: Double, row: StripRow, spec: StripSpec): Double {
        val y = row.baselineMm - valueMv * spec.gainMmPerMv
        return y.coerceIn(row.topMm, row.topMm + spec.rowHeightMm)
    }

    /** True when [yMm] had to clamp, i.e. the trace runs off the lane. */
    fun isClipped(valueMv: Double, spec: StripSpec): Boolean =
        !valueMv.isFinite() || valueMv > spec.halfRangeMv || valueMv < -spec.halfRangeMv

    /**
     * The standard calibration mark: baseline, a square step of exactly one
     * millivolt held for exactly 200 ms, then back to baseline. It sits in the
     * gutter so the trace itself still starts at t=0.
     */
    fun calibrationPulseMm(row: StripRow, spec: StripSpec): List<PointMm> {
        val widthMm = StripSpec.CAL_PULSE_SEC * spec.speedMmPerSec
        val riseX = (spec.calGutterMm - widthMm).coerceAtLeast(0.0)
        val topY = row.baselineMm - StripSpec.CAL_PULSE_MV * spec.gainMmPerMv
        return listOf(
            PointMm(0.0, row.baselineMm),
            PointMm(riseX, row.baselineMm),
            PointMm(riseX, topY),
            PointMm(spec.calGutterMm, topY),
            PointMm(spec.calGutterMm, row.baselineMm),
        )
    }

    /**
     * Grid lines over the whole sheet. Major lines land on multiples of 5 mm
     * measured from the trace origin, not from the sheet edge, so one large box
     * is always 0.2 s of signal and t=0 is always on a major line.
     */
    fun grid(rowCount: Int, spec: StripSpec): StripGrid {
        val width = widthMm(spec)
        val height = heightMm(rowCount, spec)
        val origin = spec.calGutterMm

        val minorX = ArrayList<Double>()
        val majorX = ArrayList<Double>()
        val firstStep = ceil(-origin / StripSpec.MINOR_GRID_MM).toInt()
        val lastStep = floor((width - origin) / StripSpec.MINOR_GRID_MM).toInt()
        for (step in firstStep..lastStep) {
            val x = origin + step * StripSpec.MINOR_GRID_MM
            if (step.mod(5) == 0) majorX.add(x) else minorX.add(x)
        }

        val minorY = ArrayList<Double>()
        val majorY = ArrayList<Double>()
        val yStepCount = floor(height / StripSpec.MINOR_GRID_MM).toInt()
        for (step in 0..yStepCount) {
            val y = step * StripSpec.MINOR_GRID_MM
            if (step.mod(5) == 0) majorY.add(y) else minorY.add(y)
        }

        return StripGrid(
            minorXMm = minorX.toDoubleArray(),
            majorXMm = majorX.toDoubleArray(),
            minorYMm = minorY.toDoubleArray(),
            majorYMm = majorY.toDoubleArray(),
        )
    }

    /**
     * Sample index range covering a row, from the sample grid rather than from
     * captured timestamps: `ECG_ON_DEMAND` batches its timestamps, so many
     * samples share one, and time taken from them would collapse onto one x.
     */
    fun rowSampleRange(row: StripRow, srHz: Double, sampleCount: Int): IntRange {
        if (sampleCount <= 0 || srHz <= 0.0) return IntRange.EMPTY
        val first = floor(row.startSec * srHz).toInt().coerceIn(0, sampleCount - 1)
        val last = ceil(row.endSec * srHz).toInt().coerceIn(0, sampleCount - 1)
        return if (first > last) IntRange.EMPTY else first..last
    }

    fun sampleTimeSec(sampleIndex: Long, srHz: Double): Double =
        if (srHz > 0.0) sampleIndex / srHz else 0.0

    /** Millimetres per pixel needed to fit a whole sheet into [availableWidthPx]. */
    fun pxPerMmToFit(availableWidthPx: Float, spec: StripSpec): Float {
        val width = widthMm(spec)
        if (width <= 0.0 || availableWidthPx <= 0f) return 0f
        return (availableWidthPx / width).toFloat()
    }

    /**
     * How many horizontal pixels one row occupies at true scale. Used to size a
     * scrolling lane, and to tell [EcgWaveformGeometry.reduceM4] how many
     * buckets the trace actually needs.
     */
    fun rowWidthPx(pxPerMm: Float, spec: StripSpec): Int =
        (widthMm(spec) * pxPerMm).roundToInt().coerceAtLeast(1)
}

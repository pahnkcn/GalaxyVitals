package app.galaxyvitals.export

import app.galaxyvitals.data.protocol.QualityFlag
import java.util.Locale

/** A heading in the report, the same on screen and on paper. */
enum class ReportSection {
    REPORT_TITLE,
    RECORDING,
    RHYTHM,
    MEASUREMENTS,
    SIGNAL_QUALITY,
    NOTE,
    SAMPLES,
    BEATS,
}

/** A named field in the header, quality block, or a spreadsheet column. */
enum class ReportField {
    RECORDED_AT,
    DURATION,
    SAMPLE_RATE,
    SAMPLE_COUNT,
    LEAD,
    WRIST,
    DEVICE,
    CAPTURE_SOURCE,
    FILTER,
    SCALE,
    SESSION_ID,
    CHECKSUM,
    ANALYSIS_BUNDLE,
    APP_VERSION,
    MODEL_SCORE,
    QUALITY_STATUS,
    CLEAN_COVERAGE,
    NOISY_SPANS,
    MAINS,
    BASELINE_EXCURSION,
    NOTE_TEXT,
    TIME_MS,
    SENSOR_REL_MS,
    RAW_MV,
    FILTERED_MV,
    SAMPLE_FLAGS,
    R_PEAK_MS,
    RR_ALL_MS,
    RR_NN_MS,
    VALUE,
    SPREAD,
    UNIT,
}

/**
 * Every word the report needs, resolved outside the exporters.
 *
 * The PDF and the spreadsheet are built from pure Kotlin so their numbers can be
 * tested off-device; wording arrives through here from Android resources, so
 * both exports follow the app's language without dragging a `Context` into the
 * layout code.
 */
interface EcgReportText {
    fun section(section: ReportSection): String
    fun field(field: ReportField): String
    fun measurement(key: MeasurementKey): String
    fun unit(key: MeasurementKey): String
    fun availability(value: MetricAvailability): String
    fun qualityFlag(flag: QualityFlag): String
    fun verdictTitle(verdict: ReportVerdict): String
    fun verdictBody(verdict: ReportVerdict): String
    fun qualityStatus(quality: ReportQuality): String
    fun disclaimer(): String
    fun timestamp(epochMs: Long): String
    fun wrist(header: ReportHeader): String
    fun captureSource(header: ReportHeader): String
    /** e.g. "25 mm/s · 10 mm/mV". */
    fun scale(header: ReportHeader): String
    fun filter(header: ReportHeader): String
    fun notMeasured(): String
}

/** Number formatting shared by the screen and the PDF. Excel gets real numbers instead. */
object ReportFormat {

    fun number(value: Double?, decimals: Int, locale: Locale = Locale.getDefault()): String? {
        if (value == null || !value.isFinite()) return null
        return String.format(locale, "%.${decimals}f", value)
    }

    /**
     * Renders a measurement in the shape its key implies, or null when the
     * recording could not support it — the caller then shows the reason.
     */
    fun value(
        measurement: Measurement,
        locale: Locale = Locale.getDefault(),
    ): String? {
        if (!measurement.isAvailable) return null
        val primary = number(measurement.value, measurement.decimals, locale) ?: return null
        val secondary = number(measurement.spread, measurement.decimals, locale)
        return when (measurement.key.form) {
            MeasurementForm.SINGLE -> primary
            MeasurementForm.RANGE -> if (secondary == null) primary else "$primary–$secondary"
            MeasurementForm.MEAN_SD -> if (secondary == null) primary else "$primary ± $secondary"
            MeasurementForm.FRACTION -> if (secondary == null) primary else "$primary / $secondary"
        }
    }

    /** `12–15 s`, for naming the stretch of a recording a flag refers to. */
    fun spanSeconds(range: ClosedRange<Long>, locale: Locale = Locale.getDefault()): String {
        val start = number(range.start / 1000.0, 0, locale)
        val end = number(range.endInclusive / 1000.0, 0, locale)
        return "$start–$end"
    }
}

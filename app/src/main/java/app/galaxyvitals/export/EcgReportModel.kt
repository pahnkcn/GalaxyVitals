package app.galaxyvitals.export

import app.galaxyvitals.data.protocol.EcgBandwidth
import app.galaxyvitals.data.protocol.NaoLabel
import app.galaxyvitals.data.protocol.QualityFlag
import app.galaxyvitals.domain.AnalysisStatus
import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.domain.SignalQualityStatus
import app.galaxyvitals.domain.Wrist

/**
 * Everything one recording reports, as numbers and keys with no wording.
 *
 * The screen, the PDF and the spreadsheet all render from this one model, so a
 * clinician reading the printout and a user reading the phone are looking at
 * the same values, and translating the app cannot change what it reports.
 */
data class EcgReportModel(
    val header: ReportHeader,
    val verdict: ReportVerdict,
    val measurements: List<Measurement>,
    val quality: ReportQuality,
    val beats: ReportBeats,
    /** Stored samples, untouched. Exported as-is so the file stays the source of truth. */
    val rawSamples: List<EcgSample>,
    /** The same samples through the signal chain at [ReportHeader.bandwidth]. */
    val displaySamples: List<EcgSample>,
) {
    fun measurement(key: MeasurementKey): Measurement? = measurements.firstOrNull { it.key == key }
}

data class ReportHeader(
    val sessionId: String,
    val tsStartMs: Long,
    val durationSec: Double,
    val sampleCount: Int,
    val nominalSrHz: Int,
    /** Rate measured from sensor timestamps. A Galaxy Watch declares 500 Hz and runs at ~501.7. */
    val effectiveSrHz: Double,
    val srMeasured: Boolean,
    val wrist: Wrist,
    val captureSource: String,
    val watchInfo: String,
    val unitLabel: String,
    val bandwidth: EcgBandwidth,
    val speedMmPerSec: Double,
    val gainMmPerMv: Double,
    val payloadSha256: String?,
    val analysisBundleId: String?,
    val appVersion: String,
    /** Typed once at export time and never stored. Empty when the user did not add one. */
    val note: String = "",
)

data class ReportVerdict(
    val analysisStatus: AnalysisStatus,
    val naoLabel: NaoLabel?,
    val modelScore: Float?,
    val rateStatus: RateStatus,
    val hrvStatus: HrvStatus,
    /** The analysis was produced by a bundle that is no longer the packaged one. */
    val staleBundle: Boolean,
)

/** Why a rate-derived number is or is not there. Mirrors `EcgBpmStatus`. */
enum class RateStatus { RELIABLE, INSUFFICIENT_DATA, LOW_QUALITY, DETECTOR_DISAGREEMENT }

/** Why an HRV number is or is not there. Mirrors `EcgHrvStatus`. */
enum class HrvStatus { RELIABLE, INSUFFICIENT_DATA, TOO_MANY_CORRECTIONS, LOW_QUALITY }

enum class MeasurementKey {
    HEART_RATE,
    HEART_RATE_RANGE,
    RR_MEAN,
    SDNN,
    RMSSD,
    PNN50,
    BEAT_COUNT,
    CORRECTED_INTERVALS,
    DETECTOR_AGREEMENT,
    ANALYSED_DURATION,
}

/**
 * One reported number. [value] is null when the recording could not support it,
 * and [availability] says why — the screen shows the reason in place of the
 * number rather than a dash.
 */
data class Measurement(
    val key: MeasurementKey,
    val value: Double?,
    /** The second half of a range or a spread, e.g. the max of min..max. */
    val spread: Double? = null,
    val decimals: Int = 0,
    val availability: MetricAvailability = MetricAvailability.AVAILABLE,
) {
    val isAvailable: Boolean get() = value != null && availability == MetricAvailability.AVAILABLE
}

enum class MetricAvailability {
    AVAILABLE,
    INSUFFICIENT_DATA,
    LOW_QUALITY,
    TOO_MANY_CORRECTIONS,
    DETECTOR_DISAGREEMENT,
}

data class ReportQuality(
    val status: SignalQualityStatus,
    val flags: List<QualityFlag>,
    val cleanCoveragePct: Double,
    val cleanUnionMs: Long,
    /** Stretches the analyser refused, in milliseconds from the start of the recording. */
    val noisyRangesMs: List<ClosedRange<Long>>,
    /** Interference frequency found in the recording itself, not assumed. */
    val mainsHz: Double?,
    val mainsSuppressionDb: Double?,
    val baselineExcursionMv: Double,
)

data class ReportBeats(
    /** R-peak positions in milliseconds from the start of the recording. */
    val rPeaksMs: List<Double>,
    val rrAllMs: List<Double>,
    /** Normal-to-normal intervals: the only series HRV may be built from. */
    val rrNnMs: List<Double>,
    val missedBeatCount: Int,
    val extraDetectionCount: Int,
    val implausibleCount: Int,
) {
    val correctedCount: Int get() = missedBeatCount + extraDetectionCount + implausibleCount
}

/** How a measurement reads once it has a label: one number, a span, or a share of a whole. */
enum class MeasurementForm { SINGLE, RANGE, MEAN_SD, FRACTION }

val MeasurementKey.form: MeasurementForm
    get() = when (this) {
        MeasurementKey.HEART_RATE_RANGE -> MeasurementForm.RANGE
        MeasurementKey.RR_MEAN -> MeasurementForm.MEAN_SD
        MeasurementKey.CORRECTED_INTERVALS,
        MeasurementKey.ANALYSED_DURATION,
        -> MeasurementForm.FRACTION
        else -> MeasurementForm.SINGLE
    }

package app.galaxyvitals.export

import android.content.Context
import androidx.annotation.StringRes
import app.galaxyvitals.R
import app.galaxyvitals.data.protocol.EcgBandwidth
import app.galaxyvitals.data.protocol.NaoLabel
import app.galaxyvitals.data.protocol.QualityFlag
import app.galaxyvitals.domain.AnalysisStatus
import app.galaxyvitals.domain.SignalQualityStatus
import app.galaxyvitals.domain.Wrist
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's own wording, handed to the exporters and to the detail screen.
 *
 * One implementation so the phone, the PDF and the spreadsheet cannot drift
 * apart: if the screen calls a rhythm "irregular", so does the printout.
 */
class AndroidReportText(private val context: Context) : EcgReportText {

    override fun section(section: ReportSection): String = string(
        when (section) {
            ReportSection.REPORT_TITLE -> R.string.report_title
            ReportSection.RECORDING -> R.string.report_section_recording
            ReportSection.RHYTHM -> R.string.report_section_rhythm
            ReportSection.MEASUREMENTS -> R.string.section_measurements
            ReportSection.SIGNAL_QUALITY -> R.string.section_signal_quality
            ReportSection.NOTE -> R.string.report_section_note
            ReportSection.SAMPLES -> R.string.report_field_raw_mv
            ReportSection.BEATS -> R.string.report_field_r_peak_ms
        },
    )

    override fun field(field: ReportField): String = string(
        when (field) {
            ReportField.RECORDED_AT -> R.string.report_field_recorded_at
            ReportField.DURATION -> R.string.report_field_duration
            ReportField.SAMPLE_RATE -> R.string.report_field_sample_rate
            ReportField.SAMPLE_COUNT -> R.string.report_field_sample_count
            ReportField.LEAD -> R.string.report_field_lead
            ReportField.WRIST -> R.string.report_field_wrist
            ReportField.DEVICE -> R.string.report_field_device
            ReportField.CAPTURE_SOURCE -> R.string.report_field_capture_source
            ReportField.FILTER -> R.string.report_field_filter
            ReportField.SCALE -> R.string.report_field_scale
            ReportField.SESSION_ID -> R.string.report_field_session_id
            ReportField.CHECKSUM -> R.string.report_field_checksum
            ReportField.ANALYSIS_BUNDLE -> R.string.report_field_analysis_bundle
            ReportField.APP_VERSION -> R.string.report_field_app_version
            ReportField.MODEL_SCORE -> R.string.report_field_model_score
            ReportField.QUALITY_STATUS -> R.string.report_field_quality_status
            ReportField.CLEAN_COVERAGE -> R.string.report_field_clean_coverage
            ReportField.NOISY_SPANS -> R.string.report_field_noisy_spans
            ReportField.MAINS -> R.string.report_field_mains
            ReportField.BASELINE_EXCURSION -> R.string.report_field_baseline_excursion
            ReportField.NOTE_TEXT -> R.string.report_field_note_text
            ReportField.TIME_MS -> R.string.report_field_time_ms
            ReportField.SENSOR_REL_MS -> R.string.report_field_sensor_rel_ms
            ReportField.RAW_MV -> R.string.report_field_raw_mv
            ReportField.FILTERED_MV -> R.string.report_field_filtered_mv
            ReportField.SAMPLE_FLAGS -> R.string.report_field_sample_flags
            ReportField.R_PEAK_MS -> R.string.report_field_r_peak_ms
            ReportField.RR_ALL_MS -> R.string.report_field_rr_all_ms
            ReportField.RR_NN_MS -> R.string.report_field_rr_nn_ms
            ReportField.VALUE -> R.string.report_field_value
            ReportField.SPREAD -> R.string.report_field_spread
            ReportField.UNIT -> R.string.report_field_unit
        },
    )

    override fun measurement(key: MeasurementKey): String = string(measurementLabel(key))

    override fun unit(key: MeasurementKey): String = string(unitLabel(key))

    override fun availability(value: MetricAvailability): String = string(
        when (value) {
            MetricAvailability.AVAILABLE -> R.string.report_not_measured
            MetricAvailability.INSUFFICIENT_DATA -> R.string.unavailable_insufficient_data
            MetricAvailability.LOW_QUALITY -> R.string.unavailable_low_quality
            MetricAvailability.TOO_MANY_CORRECTIONS -> R.string.unavailable_too_many_corrections
            MetricAvailability.DETECTOR_DISAGREEMENT -> R.string.unavailable_detector_disagreement
        },
    )

    override fun qualityFlag(flag: QualityFlag): String = string(
        when (flag) {
            QualityFlag.LEGACY_TIMING -> R.string.flag_legacy_timing
            QualityFlag.UNSUPPORTED_RATE -> R.string.flag_unsupported_rate
            QualityFlag.TIMESTAMP_GAP -> R.string.flag_timestamp_gap
            QualityFlag.MISSING_SAMPLES -> R.string.flag_missing_samples
            QualityFlag.CONTACT_LOSS -> R.string.flag_contact_loss
            QualityFlag.CLIPPING -> R.string.flag_clipping
            QualityFlag.FLATLINE -> R.string.flag_flatline
            QualityFlag.HELD_SIGNAL -> R.string.flag_held_signal
            QualityFlag.IMPULSE_NOISE -> R.string.flag_impulse_noise
            QualityFlag.BASELINE_DRIFT -> R.string.flag_baseline_drift
            QualityFlag.MAINS_INTERFERENCE -> R.string.flag_mains_interference
            QualityFlag.HIGH_FREQUENCY_NOISE -> R.string.flag_high_frequency_noise
            QualityFlag.LOW_AMPLITUDE -> R.string.flag_low_amplitude
            QualityFlag.INSUFFICIENT_CLEAN_COVERAGE -> R.string.flag_insufficient_clean_coverage
        },
    )

    override fun verdictTitle(verdict: ReportVerdict): String = string(verdictTitleRes(verdict))

    override fun verdictBody(verdict: ReportVerdict): String = string(verdictBodyRes(verdict))

    override fun qualityStatus(quality: ReportQuality): String = string(
        when (quality.status) {
            SignalQualityStatus.GOOD -> R.string.quality_status_good
            SignalQualityStatus.LOW_QUALITY -> R.string.quality_status_low
            SignalQualityStatus.INVALID -> R.string.quality_status_invalid
            SignalQualityStatus.UNKNOWN -> R.string.quality_status_unknown
        },
    )

    override fun disclaimer(): String = string(R.string.ecg_disclaimer)

    override fun timestamp(epochMs: Long): String =
        SimpleDateFormat(TIMESTAMP_PATTERN, Locale.getDefault()).format(Date(epochMs))

    override fun wrist(header: ReportHeader): String = string(
        when (header.wrist) {
            Wrist.LEFT -> R.string.report_wrist_left
            Wrist.RIGHT -> R.string.report_wrist_right
            else -> R.string.report_wrist_unknown
        },
    )

    override fun captureSource(header: ReportHeader): String = string(
        when (header.captureSource) {
            "HARDWARE" -> R.string.report_source_hardware
            "IMPORT" -> R.string.report_source_import
            else -> R.string.report_source_legacy
        },
    )

    override fun scale(header: ReportHeader): String = context.getString(
        R.string.strip_scale,
        ReportFormat.number(header.speedMmPerSec, if (header.speedMmPerSec % 1.0 == 0.0) 0 else 1),
        ReportFormat.number(header.gainMmPerMv, 0),
    )

    override fun filter(header: ReportHeader): String = string(
        when (header.bandwidth) {
            EcgBandwidth.DIAGNOSTIC -> R.string.report_filter_diagnostic
            EcgBandwidth.MONITOR -> R.string.report_filter_monitor
        },
    )

    override fun notMeasured(): String = string(R.string.report_not_measured)

    private fun string(@StringRes id: Int): String = context.getString(id)

    companion object {
        private const val TIMESTAMP_PATTERN = "d MMM yyyy · HH:mm"

        @StringRes
        fun measurementLabel(key: MeasurementKey): Int = when (key) {
            MeasurementKey.HEART_RATE -> R.string.measure_heart_rate
            MeasurementKey.HEART_RATE_RANGE -> R.string.measure_heart_rate_range
            MeasurementKey.RR_MEAN -> R.string.measure_rr_mean
            MeasurementKey.SDNN -> R.string.measure_sdnn
            MeasurementKey.RMSSD -> R.string.measure_rmssd
            MeasurementKey.PNN50 -> R.string.measure_pnn50
            MeasurementKey.BEAT_COUNT -> R.string.measure_beat_count
            MeasurementKey.CORRECTED_INTERVALS -> R.string.measure_corrected_intervals
            MeasurementKey.DETECTOR_AGREEMENT -> R.string.measure_detector_agreement
            MeasurementKey.ANALYSED_DURATION -> R.string.measure_analysed_duration
        }

        @StringRes
        fun unitLabel(key: MeasurementKey): Int = when (key) {
            MeasurementKey.HEART_RATE, MeasurementKey.HEART_RATE_RANGE -> R.string.unit_bpm
            MeasurementKey.RR_MEAN, MeasurementKey.SDNN, MeasurementKey.RMSSD -> R.string.unit_ms
            MeasurementKey.PNN50, MeasurementKey.DETECTOR_AGREEMENT -> R.string.unit_percent
            MeasurementKey.BEAT_COUNT -> R.string.unit_beats
            MeasurementKey.CORRECTED_INTERVALS -> R.string.unit_none
            MeasurementKey.ANALYSED_DURATION -> R.string.unit_seconds
        }

        /** What each measurement means, for someone who has never read an ECG. */
        @StringRes
        fun explanation(key: MeasurementKey): Int = when (key) {
            MeasurementKey.HEART_RATE -> R.string.explain_heart_rate
            MeasurementKey.HEART_RATE_RANGE -> R.string.explain_heart_rate_range
            MeasurementKey.RR_MEAN -> R.string.explain_rr_mean
            MeasurementKey.SDNN -> R.string.explain_sdnn
            MeasurementKey.RMSSD -> R.string.explain_rmssd
            MeasurementKey.PNN50 -> R.string.explain_pnn50
            MeasurementKey.BEAT_COUNT -> R.string.explain_beat_count
            MeasurementKey.CORRECTED_INTERVALS -> R.string.explain_corrected_intervals
            MeasurementKey.DETECTOR_AGREEMENT -> R.string.explain_detector_agreement
            MeasurementKey.ANALYSED_DURATION -> R.string.explain_analysed_duration
        }

        /**
         * The rhythm headline. Status wins over the model label: a recording the
         * model could not read honestly says so rather than reporting a class.
         */
        @StringRes
        fun verdictTitleRes(verdict: ReportVerdict): Int = when (verdict.analysisStatus) {
            AnalysisStatus.LOW_QUALITY -> R.string.verdict_low_quality
            AnalysisStatus.PENDING -> R.string.verdict_pending
            AnalysisStatus.FAILED -> R.string.verdict_not_analysed
            AnalysisStatus.INDETERMINATE -> R.string.verdict_indeterminate
            AnalysisStatus.NONE -> R.string.verdict_not_analysed
            AnalysisStatus.OK -> when (verdict.naoLabel) {
                NaoLabel.N -> R.string.verdict_regular
                NaoLabel.A -> R.string.verdict_irregular
                NaoLabel.O -> R.string.verdict_inconclusive
                null -> R.string.verdict_indeterminate
            }
        }

        @StringRes
        fun verdictBodyRes(verdict: ReportVerdict): Int = when (verdict.analysisStatus) {
            AnalysisStatus.LOW_QUALITY -> R.string.verdict_low_quality_body
            AnalysisStatus.PENDING -> R.string.verdict_pending_body
            AnalysisStatus.FAILED -> R.string.verdict_not_analysed_body
            AnalysisStatus.INDETERMINATE -> R.string.verdict_inconclusive_body
            AnalysisStatus.NONE -> R.string.verdict_none_body
            AnalysisStatus.OK -> when (verdict.naoLabel) {
                NaoLabel.N -> R.string.verdict_regular_body
                NaoLabel.A -> R.string.verdict_irregular_body
                NaoLabel.O -> R.string.verdict_inconclusive_body
                null -> R.string.verdict_inconclusive_body
            }
        }
    }
}

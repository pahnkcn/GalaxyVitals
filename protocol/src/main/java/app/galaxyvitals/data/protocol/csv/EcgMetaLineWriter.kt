package app.galaxyvitals.data.protocol.csv

import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.domain.CaptureSource
import app.galaxyvitals.domain.LiveBpmObservation
import app.galaxyvitals.domain.LiveBpmSummary
import app.galaxyvitals.domain.TimingTrust
import app.galaxyvitals.domain.Wrist

/**
 * The `#meta=` and `#bpm=` lines, written by hand.
 *
 * These bytes are the contract, not a serialisation of it: the phone hashes the
 * file with SHA-256 to de-duplicate watch pushes and to detect session-id
 * collisions, so key order and `null` spelling are as load-bearing as the
 * values. That is why there is no reflective writer here and why the three
 * schema versions are spelled out separately instead of being folded together -
 * v2 and v3 do not merely add keys, they order and mean them differently.
 *
 * EcgCsvWriterGoldenTest pins all three, character for character.
 */
internal object EcgMetaLineWriter {

    fun metaLine(
        srHz: Int,
        unit: String,
        tsStartMs: Long,
        hrStartRelMs: Long,
        droppedBeforeHr: Int,
        rowsWithHrPct: Double,
        watchInfo: String,
        wrist: Wrist,
        signFactor: Int,
    ): String {
        val wristName = if (wrist == Wrist.RIGHT) "RIGHT" else "LEFT"
        return buildString {
            append("#meta={")
            append("\"sr_hz\":").append(srHz).append(',')
            append("\"unit\":\"").append(escape(unit)).append("\",")
            append("\"ts_start\":").append(tsStartMs).append(',')
            append("\"format\":\"csv_mv\",")
            append("\"hr_start_rel_ms\":").append(hrStartRelMs).append(',')
            append("\"dropped_rows_before_hr\":").append(droppedBeforeHr).append(',')
            append("\"rows_with_hr_pct\":").append(rowsWithHrPct).append(',')
            append("\"watch_info\":\"").append(escape(watchInfo)).append("\",")
            append("\"wrist\":\"").append(wristName).append("\",")
            append("\"signFactor\":").append(signFactor).append(',')
            append("\"polarityNormalized\":true")
            append("}\n")
        }
    }

    fun escape(raw: String): String = buildString(raw.length) {
        raw.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
    }

    fun metaLineV2(
        srHz: Int,
        effectiveSrHz: Double,
        tsStartMs: Long,
        sensorStartMs: Long,
        sampleCount: Int,
        durationMs: Long,
        watchInfo: String,
        wrist: Wrist,
        signFactor: Int,
        captureSource: CaptureSource,
        gapCount: Int,
        missingSampleCount: Int,
        sequenceGapCount: Int,
        contactLossCount: Int,
        clippedSampleCount: Int,
        acquisitionFlags: Int,
        minThresholdMv: Float?,
        maxThresholdMv: Float?,
    ): String {
        val wristName = if (wrist == Wrist.RIGHT) "RIGHT" else "LEFT"
        return buildString {
            append("#meta={")
            append("\"schema_version\":2,")
            append("\"sr_hz\":").append(srHz).append(',')
            append("\"effective_sr_hz\":").append(effectiveSrHz).append(',')
            append("\"unit\":\"mV\",")
            append("\"ts_start\":").append(tsStartMs).append(',')
            append("\"sensor_start_ms\":").append(sensorStartMs).append(',')
            append("\"clock_source\":\"SAMSUNG_DATAPOINT_MS\",")
            append("\"timing_trust\":\"").append(TimingTrust.SENSOR.name).append("\",")
            append("\"format\":\"csv_mv_v2\",")
            append("\"capture_source\":\"").append(captureSource.name).append("\",")
            append("\"sample_count\":").append(sampleCount).append(',')
            append("\"duration_ms\":").append(durationMs).append(',')
            append("\"gap_count\":").append(gapCount).append(',')
            append("\"missing_sample_count\":").append(missingSampleCount).append(',')
            append("\"sequence_gap_count\":").append(sequenceGapCount).append(',')
            append("\"contact_loss_count\":").append(contactLossCount).append(',')
            append("\"clipped_sample_count\":").append(clippedSampleCount).append(',')
            append("\"acquisition_flags\":").append(acquisitionFlags).append(',')
            append("\"min_threshold_mv\":")
            if (minThresholdMv == null) append("null") else append(minThresholdMv)
            append(',')
            append("\"max_threshold_mv\":")
            if (maxThresholdMv == null) append("null") else append(maxThresholdMv)
            append(',')
            append("\"watch_info\":\"").append(escape(watchInfo)).append("\",")
            append("\"wrist\":\"").append(wristName).append("\",")
            append("\"signFactor\":").append(signFactor).append(',')
            append("\"polarityNormalized\":false")
            append("}\n")
        }
    }

    fun metaLineV3(
        srHz: Int,
        effectiveSrHz: Double,
        tsStartMs: Long,
        sensorStartMs: Long,
        sampleCount: Int,
        durationMs: Long,
        watchInfo: String,
        wrist: Wrist,
        signFactor: Int,
        captureSource: CaptureSource,
        gapCount: Int,
        missingSampleCount: Int,
        sequenceGapCount: Int,
        contactLossCount: Int,
        clippedSampleCount: Int,
        acquisitionFlags: Int,
        minThresholdMv: Float?,
        maxThresholdMv: Float?,
        rawTimingTrust: TimingTrust,
        rawSensorDurationMs: Long,
        listenerDurationMs: Long,
        repeatedTimestampCount: Int,
        batchCount: Int,
        sensorSdk: String?,
        sensorAarSha256: String?,
        liveBpmAlgorithmId: String?,
        liveBpmSummary: LiveBpmSummary,
    ): String {
        val wristName = if (wrist == Wrist.RIGHT) "RIGHT" else "LEFT"
        return buildString {
            append("#meta={")
            append("\"schema_version\":").append(EcgWearContract.SCHEMA_VERSION_V3).append(',')
            append("\"sr_hz\":").append(srHz).append(',')
            append("\"effective_sr_hz\":").append(effectiveSrHz).append(',')
            append("\"unit\":\"mV\",")
            append("\"ts_start\":").append(tsStartMs).append(',')
            append("\"sensor_start_ms\":").append(sensorStartMs).append(',')
            append("\"format\":\"").append(EcgWearContract.FORMAT_CSV_MV_V3).append("\",")
            append("\"capture_source\":\"").append(captureSource.name).append("\",")
            append("\"timing_trust\":\"").append(TimingTrust.SEQUENCE_RECONSTRUCTED.name).append("\",")
            append("\"analysis_clock_source\":\"").append(EcgWearContract.ANALYSIS_CLOCK_SOURCE).append("\",")
            append("\"raw_clock_source\":\"").append(EcgWearContract.RAW_CLOCK_SOURCE).append("\",")
            append("\"raw_timing_trust\":\"").append(rawTimingTrust.name).append("\",")
            append("\"raw_sensor_duration_ms\":").append(rawSensorDurationMs).append(',')
            append("\"listener_duration_ms\":").append(listenerDurationMs).append(',')
            append("\"sample_count\":").append(sampleCount).append(',')
            append("\"duration_ms\":").append(durationMs).append(',')
            append("\"gap_count\":").append(gapCount).append(',')
            append("\"missing_sample_count\":").append(missingSampleCount).append(',')
            append("\"missing_sample_count_known\":false,")
            append("\"sequence_gap_count\":").append(sequenceGapCount).append(',')
            append("\"contact_loss_count\":").append(contactLossCount).append(',')
            append("\"clipped_sample_count\":").append(clippedSampleCount).append(',')
            append("\"acquisition_flags\":").append(acquisitionFlags).append(',')
            append("\"repeated_timestamp_count\":").append(repeatedTimestampCount).append(',')
            append("\"batch_count\":").append(batchCount).append(',')
            append("\"min_threshold_mv\":")
            appendNullableNumber(this, minThresholdMv)
            append(',')
            append("\"max_threshold_mv\":")
            appendNullableNumber(this, maxThresholdMv)
            append(',')
            append("\"sensor_sdk\":")
            appendNullableString(this, sensorSdk)
            append(',')
            append("\"sensor_aar_sha256\":")
            appendNullableString(this, sensorAarSha256)
            append(',')
            append("\"live_bpm_algorithm_id\":")
            appendNullableString(this, liveBpmAlgorithmId)
            append(',')
            append("\"live_bpm_observation_count\":").append(liveBpmSummary.observationCount).append(',')
            append("\"live_bpm_median\":")
            appendNullableNumber(this, liveBpmSummary.median)
            append(',')
            append("\"live_bpm_min\":")
            appendNullableNumber(this, liveBpmSummary.min)
            append(',')
            append("\"live_bpm_max\":")
            appendNullableNumber(this, liveBpmSummary.max)
            append(',')
            append("\"live_bpm_reliable_coverage_pct\":").append(liveBpmSummary.reliableCoveragePct).append(',')
            append("\"watch_info\":\"").append(escape(watchInfo)).append("\",")
            append("\"wrist\":\"").append(wristName).append("\",")
            append("\"signFactor\":").append(signFactor).append(',')
            append("\"polarityNormalized\":false")
            append("}\n")
        }
    }

    fun appendBpmLine(out: StringBuilder, id: Int, observation: LiveBpmObservation) {
        out.append("#bpm={")
        out.append("\"id\":").append(id).append(',')
        out.append("\"at_sample_index\":").append(observation.atSampleIndex).append(',')
        out.append("\"observed_capture_elapsed_ms\":").append(observation.observedCaptureElapsedMs).append(',')
        out.append("\"status\":\"").append(escape(observation.status)).append("\",")
        out.append("\"displayed_bpm\":")
        appendNullableNumber(out, observation.displayedBpm)
        out.append(',')
        out.append("\"raw_bpm\":")
        appendNullableNumber(out, observation.rawBpm)
        out.append(',')
        out.append("\"source\":")
        appendNullableString(out, observation.source)
        out.append(',')
        out.append("\"b_sqi\":")
        appendNullableNumber(out, observation.bSqi)
        out.append(',')
        out.append("\"rr_count\":")
        if (observation.rrCount == null) out.append("null") else out.append(observation.rrCount)
        out.append(',')
        out.append("\"estimate_age_ms\":").append(observation.estimateAgeMs).append(',')
        out.append("\"reason_code\":")
        appendNullableString(out, observation.reasonCode)
        out.append(',')
        out.append("\"sensor_timestamp_ms\":")
        appendNullableNumber(out, observation.sensorTimestampMs)
        out.append(',')
        out.append("\"sensor_status\":")
        appendNullableNumber(out, observation.sensorStatus)
        out.append(',')
        out.append("\"ibi_ms\":")
        appendIntArray(out, observation.ibiMs)
        out.append(',')
        out.append("\"ibi_status\":")
        appendIntArray(out, observation.ibiStatus)
        out.append("}\n")
    }

    private fun appendIntArray(out: StringBuilder, values: List<Int>) {
        out.append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) out.append(',')
            out.append(value)
        }
        out.append(']')
    }

    private fun appendNullableNumber(out: StringBuilder, value: Number?) {
        if (value == null) out.append("null") else out.append(value.toString())
    }

    private fun appendNullableString(out: StringBuilder, value: String?) {
        if (value == null) {
            out.append("null")
        } else {
            out.append('"').append(escape(value)).append('"')
        }
    }
}

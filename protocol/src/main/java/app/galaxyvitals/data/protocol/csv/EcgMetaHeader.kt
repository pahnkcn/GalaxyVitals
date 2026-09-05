package app.galaxyvitals.data.protocol.csv

import app.galaxyvitals.data.protocol.EcgCsvParser
import app.galaxyvitals.data.protocol.EcgParseException
import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.domain.CaptureSource
import app.galaxyvitals.domain.TimingTrust
import app.galaxyvitals.domain.Wrist
import java.util.Locale

private const val MIN_SR_HZ = 1
private const val MAX_SR_HZ = 2_000

/**
 * The validated `#meta=` line of one recording.
 *
 * Three schema versions are in the field and they do not merely add keys: v2
 * fixes `timing_trust` to UNVERIFIED regardless of what the file claims, and v3
 * requires SEQUENCE_RECONSTRUCTED and forbids claiming a known missing-sample
 * count. Reading the header is therefore a decision procedure rather than a
 * field copy, and it is kept whole - and away from the sample-row loop - so the
 * per-version rules can be read in one place.
 */
internal class EcgMetaHeader(
    val schemaVersion: Int,
    val srHz: Int,
    val tsStart: Long,
    val wrist: Wrist,
    val signFactor: Int,
    val polarityNormalized: Boolean,
    val watchInfo: String,
    val captureSource: CaptureSource,
    val timingTrust: TimingTrust,
    val effectiveSrHz: Double,
    val sensorStartMs: Long?,
    val analysisClockSource: String?,
    val rawClockSource: String?,
    val rawTimingTrust: TimingTrust?,
    val rawSensorDurationMs: Long?,
    val listenerDurationMs: Long?,
    val missingSampleCountKnown: Boolean,
    val repeatedTimestampCount: Int,
    val batchCount: Int,
    val sensorSdk: String?,
    val sensorAarSha256: String?,
    val liveBpmAlgorithmId: String?,
    val declaredSampleCount: Int?,
    val declaredDurationMs: Long?,
    val gapCount: Int,
    val missingSampleCount: Int,
    val sequenceGapCount: Int,
    val contactLossCount: Int,
    val clippedSampleCount: Int,
    val acquisitionFlags: Int,
    val minThresholdMv: Float?,
    val maxThresholdMv: Float?,
)

/**
 * Reads and validates the metadata object.
 *
 * Order matters: the first failure is the one the user is shown, and the import
 * error mapping keys off those messages.
 */
internal fun readEcgMetaHeader(meta: MetaJson): EcgMetaHeader {
    val schemaVersion = meta.int("schema_version", 1)
    if (schemaVersion !in 1..3) {
        throw EcgParseException("Unsupported ECG schema version: $schemaVersion")
    }
    if (schemaVersion == 2) {
        listOf(
            "schema_version",
            "sr_hz",
            "effective_sr_hz",
            "unit",
            "ts_start",
            "sensor_start_ms",
            "clock_source",
            "capture_source",
            "timing_trust",
            "sample_count",
            "duration_ms",
            "wrist",
            "signFactor",
            "polarityNormalized",
        ).forEach { key ->
            if (!meta.has(key)) throw EcgParseException("Missing $key metadata for ECG schema v2")
        }
    }
    if (schemaVersion == 3) {
        listOf(
            "schema_version",
            "sr_hz",
            "effective_sr_hz",
            "unit",
            "ts_start",
            "sensor_start_ms",
            "format",
            "capture_source",
            "timing_trust",
            "analysis_clock_source",
            "raw_clock_source",
            "raw_timing_trust",
            "raw_sensor_duration_ms",
            "listener_duration_ms",
            "sample_count",
            "duration_ms",
            "missing_sample_count_known",
            "wrist",
            "signFactor",
            "polarityNormalized",
        ).forEach { key ->
            if (!meta.has(key)) throw EcgParseException("Missing $key metadata for ECG schema v3")
        }
    }
    val srHz = meta.int("sr_hz", EcgWearContract.DEFAULT_SR_HZ)
    if (srHz !in MIN_SR_HZ..MAX_SR_HZ) {
        throw EcgParseException("Invalid ECG sample rate: $srHz")
    }
    val unit = meta.string("unit", "mV")
    if (!unit.equals("mV", ignoreCase = true)) {
        throw EcgParseException("Unsupported ECG amplitude unit: $unit")
    }
    val format = meta.string("format", "csv_mv")
    if (format !in setOf("csv_mv", "csv_mv_v2", EcgWearContract.FORMAT_CSV_MV_V3)) {
        throw EcgParseException("Unsupported ECG row format: $format")
    }
    if (schemaVersion == 2 && format != "csv_mv_v2") {
        throw EcgParseException("ECG schema v2 requires csv_mv_v2 rows")
    }
    if (schemaVersion == 3 && format != EcgWearContract.FORMAT_CSV_MV_V3) {
        throw EcgParseException("ECG schema v3 requires csv_mv_v3 rows")
    }
    val tsStart = meta.long("ts_start", 0L)
    if (tsStart < 0L) throw EcgParseException("Invalid ECG start timestamp")
    val wrist = parseWrist(meta.string("wrist", "LEFT"))
    val signFactor = meta.int("signFactor", meta.int("sign_factor", 1))
    if (signFactor != -1 && signFactor != 1) {
        throw EcgParseException("Invalid ECG polarity sign factor")
    }
    val polarityNormalized = meta.bool(
        "polarityNormalized",
        meta.bool("polarity_normalized", false),
    )
    val watchInfo = meta.string("watch_info", "")
    val captureSource = parseCaptureSource(
        meta.string("capture_source", if (schemaVersion == 1) "LEGACY" else ""),
    )
    if (schemaVersion >= 2 && captureSource == CaptureSource.LEGACY) {
        throw EcgParseException("ECG schema v$schemaVersion requires HARDWARE or IMPORT capture source")
    }
    val declaredTimingTrust = parseTimingTrust(
        meta.string("timing_trust", if (schemaVersion == 1) "ASSUMED" else ""),
    )
    val timingTrust = if (schemaVersion == 2) TimingTrust.UNVERIFIED else declaredTimingTrust
    if (schemaVersion == 3 && timingTrust != TimingTrust.SEQUENCE_RECONSTRUCTED) {
        throw EcgParseException("ECG schema v3 requires SEQUENCE_RECONSTRUCTED timing trust")
    }
    val effectiveSrHz = meta.double("effective_sr_hz", srHz.toDouble())
    if (!effectiveSrHz.isFinite() || effectiveSrHz !in MIN_SR_HZ.toDouble()..MAX_SR_HZ.toDouble()) {
        throw EcgParseException("Invalid effective ECG sample rate")
    }
    val sensorStartMs = meta.nullableLong("sensor_start_ms")
    if (sensorStartMs != null && sensorStartMs < 0L) {
        throw EcgParseException("Invalid ECG sensor start timestamp")
    }
    if (schemaVersion == 2 && meta.string("clock_source", "").isBlank()) {
        throw EcgParseException("Invalid ECG clock source")
    }
    val analysisClockSource = meta.nullableString("analysis_clock_source")
    val rawClockSource = meta.nullableString("raw_clock_source")
    if (schemaVersion == 3) {
        if (analysisClockSource.isNullOrBlank()) {
            throw EcgParseException("Invalid ECG analysis clock source")
        }
        if (rawClockSource.isNullOrBlank()) {
            throw EcgParseException("Invalid ECG raw clock source")
        }
    }
    val rawTimingTrust = meta.nullableString("raw_timing_trust")?.let(::parseTimingTrust)
    val rawSensorDurationMs = meta.nullableLong("raw_sensor_duration_ms")
    if (rawSensorDurationMs != null && rawSensorDurationMs < 0L) {
        throw EcgParseException("Invalid ECG raw sensor duration")
    }
    val listenerDurationMs = meta.nullableLong("listener_duration_ms")
    if (listenerDurationMs != null && listenerDurationMs < 0L) {
        throw EcgParseException("Invalid ECG listener duration")
    }
    val missingSampleCountKnown = if (schemaVersion == 3) {
        meta.bool("missing_sample_count_known", true).also { known ->
            if (known) {
                throw EcgParseException("ECG schema v3 must declare missing_sample_count_known=false")
            }
        }
    } else {
        meta.bool("missing_sample_count_known", true)
    }
    val repeatedTimestampCount = meta.int("repeated_timestamp_count", 0)
        .requireNonnegative("repeated_timestamp_count")
    val batchCount = meta.int("batch_count", 0).requireNonnegative("batch_count")
    val sensorSdk = meta.nullableString("sensor_sdk")
    val sensorAarSha256 = meta.nullableString("sensor_aar_sha256")
    val liveBpmAlgorithmId = meta.nullableString("live_bpm_algorithm_id")
    val declaredSampleCount = meta.nullableInt("sample_count")
    if (declaredSampleCount != null && declaredSampleCount !in 1..EcgCsvParser.MAX_SAMPLES) {
        throw EcgParseException("Invalid ECG sample count metadata")
    }
    val declaredDurationMs = meta.nullableLong("duration_ms")
    if (declaredDurationMs != null && declaredDurationMs !in 0..EcgCsvParser.MAX_DURATION_MS) {
        throw EcgParseException("Invalid ECG duration metadata")
    }
    val gapCount = meta.int("gap_count", 0).requireNonnegative("gap_count")
    val missingSampleCount = meta.int("missing_sample_count", 0)
        .requireNonnegative("missing_sample_count")
    val sequenceGapCount = meta.int("sequence_gap_count", 0)
        .requireNonnegative("sequence_gap_count")
    val contactLossCount = meta.int("contact_loss_count", 0)
        .requireNonnegative("contact_loss_count")
    val clippedSampleCount = meta.int("clipped_sample_count", 0)
        .requireNonnegative("clipped_sample_count")
    val acquisitionFlags = meta.int("acquisition_flags", 0)
        .requireNonnegative("acquisition_flags")
    val minThresholdMv = meta.nullableDouble("min_threshold_mv")?.toFloat()
    val maxThresholdMv = meta.nullableDouble("max_threshold_mv")?.toFloat()
    if (minThresholdMv != null && maxThresholdMv != null && minThresholdMv >= maxThresholdMv) {
        throw EcgParseException("Invalid ECG saturation thresholds")
    }

    return EcgMetaHeader(
        schemaVersion = schemaVersion,
        srHz = srHz,
        tsStart = tsStart,
        wrist = wrist,
        signFactor = signFactor,
        polarityNormalized = polarityNormalized,
        watchInfo = watchInfo,
        captureSource = captureSource,
        timingTrust = timingTrust,
        effectiveSrHz = effectiveSrHz,
        sensorStartMs = sensorStartMs,
        analysisClockSource = analysisClockSource,
        rawClockSource = rawClockSource,
        rawTimingTrust = rawTimingTrust,
        rawSensorDurationMs = rawSensorDurationMs,
        listenerDurationMs = listenerDurationMs,
        missingSampleCountKnown = missingSampleCountKnown,
        repeatedTimestampCount = repeatedTimestampCount,
        batchCount = batchCount,
        sensorSdk = sensorSdk,
        sensorAarSha256 = sensorAarSha256,
        liveBpmAlgorithmId = liveBpmAlgorithmId,
        declaredSampleCount = declaredSampleCount,
        declaredDurationMs = declaredDurationMs,
        gapCount = gapCount,
        missingSampleCount = missingSampleCount,
        sequenceGapCount = sequenceGapCount,
        contactLossCount = contactLossCount,
        clippedSampleCount = clippedSampleCount,
        acquisitionFlags = acquisitionFlags,
        minThresholdMv = minThresholdMv,
        maxThresholdMv = maxThresholdMv,
    )
}

private fun Int.requireNonnegative(name: String): Int {
    if (this < 0) throw EcgParseException("Invalid $name metadata")
    return this
}

private fun parseCaptureSource(raw: String): CaptureSource =
    runCatching { CaptureSource.valueOf(raw.uppercase(Locale.US)) }
        .getOrElse { throw EcgParseException("Invalid ECG capture source") }

private fun parseTimingTrust(raw: String): TimingTrust =
    runCatching { TimingTrust.valueOf(raw.uppercase(Locale.US)) }
        .getOrElse { throw EcgParseException("Invalid ECG timing trust") }

private fun parseWrist(raw: String): Wrist {
    return when (raw.trim().uppercase(Locale.US)) {
        "LEFT", "L" -> Wrist.LEFT
        "RIGHT", "R" -> Wrist.RIGHT
        else -> Wrist.UNKNOWN
    }
}

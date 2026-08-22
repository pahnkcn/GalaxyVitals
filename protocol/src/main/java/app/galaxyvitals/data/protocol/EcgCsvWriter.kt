package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.domain.CaptureSource
import app.galaxyvitals.domain.TimingTrust
import app.galaxyvitals.domain.Wrist
import java.io.ByteArrayOutputStream
import java.util.Arrays
import java.util.zip.GZIPOutputStream
import kotlin.math.max

data class HrStamp(
    val epochMs: Long,
    val bpm: Int,
)

/**
 * Watch-side encoder matching the 1.0.1.50 csv+gz contract.
 *
 * Sample clock is `i * 1000 / srHz`. Heart-rate stamps are absolute epoch millis.
 * Rows before the first HR are dropped; remaining rows are shifted so the first
 * kept sample is `rel_ms = 0` and `ts_start` is that sample's actual timestamp.
 */
object EcgCsvWriter {

    fun gzipBytes(utf8: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(utf8.size)
        GZIPOutputStream(out).use { it.write(utf8) }
        return out.toByteArray()
    }

    fun encodeParsed(parsed: ParsedEcgFile): ByteArray {
        if (parsed.schemaVersion >= 2) {
            val relMs = LongArray(parsed.samples.size) { parsed.samples[it].relMs }
            val flags = IntArray(parsed.samples.size) { parsed.samples[it].flags }
            return encodeCaptureV2(
                wallStartMs = parsed.tsStartMs,
                sensorStartMs = parsed.sensorStartMs ?: 0L,
                valuesMv = FloatArray(parsed.samples.size) { parsed.samples[it].valueMv },
                relMs = relMs,
                sampleFlags = flags,
                wrist = parsed.wrist,
                signFactor = parsed.signFactor,
                watchInfo = parsed.watchInfo,
                captureSource = parsed.captureSource,
                nominalSrHz = parsed.srHz,
                gapCount = parsed.gapCount,
                missingSampleCount = parsed.missingSampleCount,
                sequenceGapCount = parsed.sequenceGapCount,
                contactLossCount = parsed.contactLossCount,
                clippedSampleCount = parsed.clippedSampleCount,
                acquisitionFlags = parsed.acquisitionFlags,
                minThresholdMv = parsed.minThresholdMv,
                maxThresholdMv = parsed.maxThresholdMv,
            )
        }
        val body = buildString {
            append(metaLine(
                srHz = parsed.srHz,
                unit = parsed.unit,
                tsStartMs = parsed.tsStartMs,
                hrStartRelMs = 0L,
                droppedBeforeHr = 0,
                rowsWithHrPct = parsed.hrCoveragePct,
                watchInfo = parsed.watchInfo,
                wrist = parsed.wrist,
                signFactor = parsed.signFactor,
            ))
            append("rel_ms,value_mv,hr_bpm\n")
            parsed.samples.forEach { sample ->
                appendRow(this, sample)
            }
        }
        return body.toByteArray(Charsets.UTF_8)
    }

    fun encodeCaptureV2(
        wallStartMs: Long,
        sensorStartMs: Long,
        valuesMv: FloatArray,
        relMs: LongArray,
        sampleFlags: IntArray,
        wrist: Wrist,
        signFactor: Int,
        watchInfo: String,
        captureSource: CaptureSource,
        nominalSrHz: Int = EcgWearContract.DEFAULT_SR_HZ,
        gapCount: Int = 0,
        missingSampleCount: Int = 0,
        sequenceGapCount: Int = 0,
        contactLossCount: Int = 0,
        clippedSampleCount: Int = 0,
        acquisitionFlags: Int = 0,
        minThresholdMv: Float? = null,
        maxThresholdMv: Float? = null,
    ): ByteArray {
        require(valuesMv.isNotEmpty()) { "No ECG samples" }
        require(valuesMv.size == relMs.size && valuesMv.size == sampleFlags.size) {
            "ECG sample arrays must have equal sizes"
        }
        require(signFactor == -1 || signFactor == 1) { "Invalid ECG polarity sign factor" }
        require(captureSource != CaptureSource.LEGACY) { "Schema v2 requires an explicit capture source" }
        var previous = -1L
        valuesMv.indices.forEach { index ->
            require(valuesMv[index].isFinite()) { "ECG amplitude must be finite" }
            require(relMs[index] >= 0L && relMs[index] >= previous) {
                "ECG timestamps must be monotonic"
            }
            require(sampleFlags[index] >= 0) { "ECG flags must be nonnegative" }
            previous = relMs[index]
        }
        val durationMs = relMs.last() - relMs.first()
        val effectiveSrHz = if (durationMs > 0L && valuesMv.size > 1) {
            (valuesMv.size - 1) * 1000.0 / durationMs
        } else {
            nominalSrHz.toDouble()
        }
        val body = buildString(valuesMv.size * 24) {
            append(metaLineV2(
                srHz = nominalSrHz,
                effectiveSrHz = effectiveSrHz,
                tsStartMs = wallStartMs,
                sensorStartMs = sensorStartMs,
                sampleCount = valuesMv.size,
                durationMs = durationMs,
                watchInfo = watchInfo,
                wrist = wrist,
                signFactor = signFactor,
                captureSource = captureSource,
                gapCount = gapCount,
                missingSampleCount = missingSampleCount,
                sequenceGapCount = sequenceGapCount,
                contactLossCount = contactLossCount,
                clippedSampleCount = clippedSampleCount,
                acquisitionFlags = acquisitionFlags,
                minThresholdMv = minThresholdMv,
                maxThresholdMv = maxThresholdMv,
            ))
            append("rel_ms,sample_index,ecg_raw_mv,flags,hr_bpm\n")
            valuesMv.indices.forEach { index ->
                append(relMs[index] - relMs.first()).append(',')
                append(index).append(',')
                append(valuesMv[index]).append(',')
                append(sampleFlags[index]).append(',').append('\n')
            }
        }
        return body.toByteArray(Charsets.UTF_8)
    }

    fun encodeCapture(
        sessionStartMs: Long,
        valuesMv: FloatArray,
        hrStamps: List<HrStamp>,
        wrist: Wrist,
        signFactor: Int,
        watchInfo: String,
        srHz: Int = EcgWearContract.DEFAULT_SR_HZ,
    ): ByteArray {
        val rate = max(1, srHz)
        val sortedHr = normalizeHrStamps(hrStamps)
        val hrEpochs = LongArray(sortedHr.size) { sortedHr[it].epochMs }
        val hrBpms = IntArray(sortedHr.size) { sortedHr[it].bpm }

        var withHr = 0
        val kept = ArrayList<EcgSample>(valuesMv.size)
        for (i in valuesMv.indices) {
            val relMs = i.toLong() * 1000L / rate
            val hr = lookupHr(hrEpochs, hrBpms, sessionStartMs + relMs)
            if (hr != null) withHr++
            kept.add(EcgSample(relMs, valuesMv[i], hr, i))
        }
        if (kept.isEmpty()) {
            throw EcgParseException("No ECG samples")
        }
        val coverage = withHr * 100.0 / kept.size
        val body = buildString {
            append(metaLine(
                srHz = rate,
                unit = "mV",
                tsStartMs = sessionStartMs,
                hrStartRelMs = 0L,
                droppedBeforeHr = 0,
                rowsWithHrPct = coverage,
                watchInfo = watchInfo,
                wrist = wrist,
                signFactor = signFactor,
            ))
            append("rel_ms,value_mv,hr_bpm\n")
            kept.forEach { appendRow(this, it) }
        }
        return body.toByteArray(Charsets.UTF_8)
    }

    fun encodeCaptureGzip(
        sessionStartMs: Long,
        valuesMv: FloatArray,
        hrStamps: List<HrStamp>,
        wrist: Wrist,
        signFactor: Int,
        watchInfo: String,
        srHz: Int = EcgWearContract.DEFAULT_SR_HZ,
    ): ByteArray = gzipBytes(
        encodeCapture(sessionStartMs, valuesMv, hrStamps, wrist, signFactor, watchInfo, srHz),
    )

    private fun lookupHr(epochs: LongArray, bpms: IntArray, atEpoch: Long): Int? {
        if (epochs.isEmpty()) return null
        var idx = Arrays.binarySearch(epochs, atEpoch)
        if (idx < 0) idx = -idx - 2
        return if (idx >= 0) bpms[idx] else null
    }

    /** Sorts stamps and makes duplicate timestamps deterministic: the latest value wins. */
    private fun normalizeHrStamps(stamps: List<HrStamp>): List<HrStamp> {
        val normalized = ArrayList<HrStamp>(stamps.size)
        stamps.sortedBy(HrStamp::epochMs).forEach { stamp ->
            if (normalized.lastOrNull()?.epochMs == stamp.epochMs) {
                normalized[normalized.lastIndex] = stamp
            } else {
                normalized.add(stamp)
            }
        }
        return normalized
    }

    private fun appendRow(out: StringBuilder, sample: EcgSample) {
        out.append(sample.relMs).append(',')
        out.append(sample.valueMv.toString()).append(',')
        if (sample.hrBpm != null) out.append(sample.hrBpm)
        out.append('\n')
    }

    private fun metaLine(
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

    internal fun escape(raw: String): String = buildString(raw.length) {
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

    private fun metaLineV2(
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
}

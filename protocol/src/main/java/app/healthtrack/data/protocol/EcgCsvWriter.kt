package app.healthtrack.data.protocol

import app.healthtrack.domain.EcgSample
import app.healthtrack.domain.Wrist
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
        val hrEmpty = sortedHr.isEmpty()
        val alignmentEpoch = if (hrEmpty) {
            sessionStartMs
        } else {
            max(sessionStartMs, sortedHr[0].epochMs)
        }
        val dropBeforeRel = alignmentEpoch - sessionStartMs
        val hrEpochs = LongArray(sortedHr.size) { sortedHr[it].epochMs }
        val hrBpms = IntArray(sortedHr.size) { sortedHr[it].bpm }

        var dropped = 0
        var withHr = 0
        val kept = ArrayList<EcgSample>(valuesMv.size)
        var firstKeptRelMs: Long? = null
        for (i in valuesMv.indices) {
            val relMs = i.toLong() * 1000L / rate
            if (relMs < dropBeforeRel) {
                dropped++
                continue
            }
            val origin = firstKeptRelMs ?: relMs.also { firstKeptRelMs = it }
            val hr = lookupHr(hrEpochs, hrBpms, sessionStartMs + relMs)
            if (hr != null) withHr++
            kept.add(EcgSample(relMs - origin, valuesMv[i], hr))
        }
        if (kept.isEmpty()) {
            throw EcgParseException("No ECG samples after HR alignment")
        }
        val actualStartMs = sessionStartMs + checkNotNull(firstKeptRelMs)
        val coverage = withHr * 100.0 / kept.size
        val body = buildString {
            append(metaLine(
                srHz = rate,
                unit = "mV",
                tsStartMs = actualStartMs,
                hrStartRelMs = 0L,
                droppedBeforeHr = dropped,
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
}

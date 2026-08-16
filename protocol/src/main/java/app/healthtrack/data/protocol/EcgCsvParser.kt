package app.healthtrack.data.protocol

import app.healthtrack.domain.EcgSample
import app.healthtrack.domain.Wrist
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.abs

data class ParsedEcgFile(
    val sessionId: String,
    val srHz: Int,
    val unit: String,
    val tsStartMs: Long,
    val wrist: Wrist,
    val signFactor: Int,
    val polarityNormalized: Boolean,
    val watchInfo: String,
    val samples: List<EcgSample>,
    val hrMedian: Double?,
    val hrMin: Int?,
    val hrMax: Int?,
    val hrCoveragePct: Double,
    val usablePct: Double,
    val durationSec: Double,
)

class EcgParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

object EcgCsvParser {

    fun parseFile(file: File, sessionIdHint: String? = null): ParsedEcgFile {
        val gzip = file.name.endsWith(".gz", ignoreCase = true)
        FileInputStream(file).use { raw ->
            return parseStream(
                input = raw,
                gzip = gzip,
                sessionIdHint = sessionIdHint ?: EcgWearContract.sessionIdFromFileName(file.name),
            )
        }
    }

    fun parseBytes(
        bytes: ByteArray,
        gzip: Boolean,
        sessionIdHint: String,
    ): ParsedEcgFile {
        return parseStream(ByteArrayInputStream(bytes), gzip, sessionIdHint)
    }

    fun parseStream(
        input: InputStream,
        gzip: Boolean,
        sessionIdHint: String,
    ): ParsedEcgFile {
        val stream = if (gzip) GZIPInputStream(input) else input
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            val first = reader.readLine() ?: throw EcgParseException("Empty file")
            if (!first.startsWith("#meta=")) {
                throw EcgParseException("Missing #meta header")
            }
            val meta = MetaJson(first.substring(6).trim())
            val srHz = meta.int("sr_hz", EcgWearContract.DEFAULT_SR_HZ)
            val unit = meta.string("unit", "mV")
            val tsStart = meta.long("ts_start", 0L)
            val wrist = parseWrist(meta.string("wrist", "LEFT"))
            val signFactor = meta.int("signFactor", meta.int("sign_factor", 1))
            val polarityNormalized = meta.bool("polarityNormalized", meta.bool("polarity_normalized", false))
            val watchInfo = meta.string("watch_info", "")

            val samples = ArrayList<EcgSample>(srHz * 30)
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val rawLine = line ?: continue
                if (rawLine.isBlank() || rawLine.startsWith("#")) continue
                if (rawLine.startsWith("rel_ms") || rawLine.startsWith("timestamp_ms")) continue
                val cols = rawLine.split(',', limit = 4)
                if (cols.size < 2) continue
                val rel = cols[0].trim().toLongOrNull() ?: continue
                val mv = cols[1].trim().toDoubleOrNull()?.toFloat() ?: continue
                val hr = cols.getOrNull(2)?.trim().let { token ->
                    if (token.isNullOrEmpty() || token.equals("NaN", ignoreCase = true)) {
                        null
                    } else {
                        token.toIntOrNull()
                    }
                }
                samples.add(EcgSample(rel, mv, hr))
            }
            if (samples.isEmpty()) {
                throw EcgParseException("No ECG samples")
            }
            return summarize(
                sessionId = sessionIdHint.ifBlank { "session" },
                srHz = srHz,
                unit = unit,
                tsStartMs = tsStart,
                wrist = wrist,
                signFactor = signFactor,
                polarityNormalized = polarityNormalized,
                watchInfo = watchInfo,
                samples = samples,
            )
        }
    }

    internal fun summarize(
        sessionId: String,
        srHz: Int,
        unit: String,
        tsStartMs: Long,
        wrist: Wrist,
        signFactor: Int,
        polarityNormalized: Boolean,
        watchInfo: String,
        samples: List<EcgSample>,
    ): ParsedEcgFile {
        val hrs = samples.mapNotNull { it.hrBpm }.sorted()
        val hrMedian = if (hrs.isEmpty()) null else median(hrs)
        val durationSec = if (samples.size <= 1) {
            0.0
        } else {
            (samples.last().relMs - samples.first().relMs) / 1000.0
        }
        val usable = samples.count { abs(it.valueMv) > 1e-6f }
        return ParsedEcgFile(
            sessionId = sessionId,
            srHz = srHz,
            unit = unit,
            tsStartMs = tsStartMs,
            wrist = wrist,
            signFactor = signFactor,
            polarityNormalized = polarityNormalized,
            watchInfo = watchInfo,
            samples = samples,
            hrMedian = hrMedian,
            hrMin = hrs.minOrNull(),
            hrMax = hrs.maxOrNull(),
            hrCoveragePct = if (samples.isEmpty()) 0.0 else hrs.size * 100.0 / samples.size,
            usablePct = if (samples.isEmpty()) 0.0 else usable * 100.0 / samples.size,
            durationSec = durationSec,
        )
    }

    private fun median(sorted: List<Int>): Double {
        val n = sorted.size
        return if (n % 2 == 1) {
            sorted[n / 2].toDouble()
        } else {
            (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        }
    }

    /** Minimal JSON object reader so the parser runs on JVM unit tests. */
    internal class MetaJson(private val raw: String) {
        fun string(key: String, default: String): String {
            val escaped = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                .find(raw)
                ?.groupValues
                ?.get(1)
            if (escaped != null) {
                return escaped.replace("\\\"", "\"").replace("\\\\", "\\")
            }
            return default
        }

        fun int(key: String, default: Int): Int =
            Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: default

        fun long(key: String, default: Long): Long =
            Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)").find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: default

        fun bool(key: String, default: Boolean): Boolean {
            val token = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
                .find(raw)
                ?.groupValues
                ?.get(1)
            return token?.toBooleanStrictOrNull() ?: default
        }
    }

    private fun parseWrist(raw: String): Wrist {
        return when (raw.trim().uppercase(Locale.US)) {
            "LEFT", "L" -> Wrist.LEFT
            "RIGHT", "R" -> Wrist.RIGHT
            else -> Wrist.UNKNOWN
        }
    }
}

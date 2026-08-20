package app.healthtrack.data.protocol

import app.healthtrack.domain.EcgSample
import app.healthtrack.domain.Wrist
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
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

class EcgParseException(message: String, cause: Throwable? = null) : IOException(message, cause)

object EcgCsvParser {

    const val MAX_COMPRESSED_BYTES = 8L * 1024L * 1024L
    const val MAX_UNCOMPRESSED_BYTES = 16L * 1024L * 1024L
    const val MAX_SAMPLES = 30_000
    const val MAX_DURATION_MS = 120_000L

    private const val MIN_SR_HZ = 1
    private const val MAX_SR_HZ = 2_000
    private const val MIN_HR_BPM = 20
    private const val MAX_HR_BPM = 300
    private const val MAX_LINE_CHARS = 16_384
    private const val MAX_JSON_DEPTH = 32

    fun parseFile(file: File, sessionIdHint: String? = null): ParsedEcgFile {
        if (file.length() > MAX_COMPRESSED_BYTES) {
            throw EcgParseException("ECG compressed data exceeds $MAX_COMPRESSED_BYTES bytes")
        }
        FileInputStream(file).use { raw ->
            return parseAutoStream(
                input = raw,
                sessionIdHint = sessionIdHint ?: EcgWearContract.sessionIdFromFileName(file.name),
            )
        }
    }

    fun parseBytes(
        bytes: ByteArray,
        gzip: Boolean,
        sessionIdHint: String,
    ): ParsedEcgFile {
        if (bytes.size.toLong() > MAX_COMPRESSED_BYTES) {
            throw EcgParseException("ECG compressed data exceeds $MAX_COMPRESSED_BYTES bytes")
        }
        return parseStream(ByteArrayInputStream(bytes), gzip, sessionIdHint)
    }

    fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    fun parseAutoStream(input: InputStream, sessionIdHint: String): ParsedEcgFile {
        val boundedRaw = LimitedInputStream(
            input = input,
            maxBytes = MAX_COMPRESSED_BYTES,
            description = "ECG compressed data",
        )
        val pushback = PushbackInputStream(boundedRaw, GZIP_MAGIC.size)
        return try {
            val prefix = ByteArray(GZIP_MAGIC.size)
            var count = 0
            while (count < prefix.size) {
                val read = pushback.read(prefix, count, prefix.size - count)
                if (read < 0) break
                count += read
            }
            if (count > 0) pushback.unread(prefix, 0, count)
            val gzip = count == GZIP_MAGIC.size && prefix.contentEquals(GZIP_MAGIC)
            parseDecodedStream(pushback, gzip, sessionIdHint)
        } catch (error: EcgParseException) {
            throw error
        } catch (error: IOException) {
            throw EcgParseException("Unable to read ECG data", error)
        }
    }

    fun parseStream(
        input: InputStream,
        gzip: Boolean,
        sessionIdHint: String,
    ): ParsedEcgFile {
        val boundedRaw = LimitedInputStream(
            input = input,
            maxBytes = MAX_COMPRESSED_BYTES,
            description = "ECG compressed data",
        )
        return parseDecodedStream(boundedRaw, gzip, sessionIdHint)
    }

    private fun parseDecodedStream(
        boundedRaw: InputStream,
        gzip: Boolean,
        sessionIdHint: String,
    ): ParsedEcgFile {
        val sessionId = try {
            EcgWearContract.requireSessionId(sessionIdHint)
        } catch (error: IllegalArgumentException) {
            throw EcgParseException("Invalid ECG session id", error)
        }
        val decoded = try {
            if (gzip) GZIPInputStream(boundedRaw) else boundedRaw
        } catch (error: EcgParseException) {
            throw error
        } catch (error: IOException) {
            throw EcgParseException("Invalid gzip ECG data", error)
        }
        val boundedDecoded = LimitedInputStream(
            input = decoded,
            maxBytes = MAX_UNCOMPRESSED_BYTES,
            description = "ECG uncompressed data",
        )
        return try {
            BufferedReader(InputStreamReader(boundedDecoded, StandardCharsets.UTF_8)).use { reader ->
                parseReader(reader, sessionId)
            }
        } catch (error: EcgParseException) {
            throw error
        } catch (error: IOException) {
            val message = if (gzip) "Invalid gzip ECG data" else "Unable to read ECG data"
            throw EcgParseException(message, error)
        }
    }

    private fun parseReader(reader: BufferedReader, sessionId: String): ParsedEcgFile {
        val first = readBoundedLine(reader) ?: throw EcgParseException("Empty file")
        if (!first.startsWith("#meta=")) {
            throw EcgParseException("Missing #meta header")
        }
        val meta = MetaJson(first.substring(6).trim())
        val srHz = meta.int("sr_hz", EcgWearContract.DEFAULT_SR_HZ)
        if (srHz !in MIN_SR_HZ..MAX_SR_HZ) {
            throw EcgParseException("Invalid ECG sample rate: $srHz")
        }
        val unit = meta.string("unit", "mV")
        if (!unit.equals("mV", ignoreCase = true)) {
            throw EcgParseException("Unsupported ECG amplitude unit: $unit")
        }
        val format = meta.string("format", "csv_mv")
        if (format != "csv_mv") throw EcgParseException("Unsupported ECG row format: $format")
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

        val samples = ArrayList<EcgSample>(minOf(MAX_SAMPLES, srHz * 30))
        var firstRelMs: Long? = null
        var previousRelMs: Long? = null
        while (true) {
            val rawLine = readBoundedLine(reader) ?: break
            if (rawLine.isBlank() || rawLine.startsWith("#")) continue
            if (rawLine.startsWith("rel_ms") || rawLine.startsWith("timestamp_ms")) continue
            val cols = rawLine.split(',', limit = 4)
            if (cols.size < 2) throw EcgParseException("Invalid ECG sample row")

            val rel = cols[0].trim().toLongOrNull()
                ?: throw EcgParseException("Invalid ECG sample timestamp")
            if (rel < 0L || previousRelMs?.let { rel < it } == true) {
                throw EcgParseException("ECG sample timestamps must be nonnegative and nondecreasing")
            }
            if (rel > MAX_DURATION_MS) {
                throw EcgParseException("ECG sample timestamp exceeds $MAX_DURATION_MS ms")
            }
            val start = firstRelMs ?: rel.also { firstRelMs = it }
            if (rel - start > MAX_DURATION_MS) {
                throw EcgParseException("ECG duration exceeds $MAX_DURATION_MS ms")
            }

            val value = cols[1].trim().toDoubleOrNull()
                ?: throw EcgParseException("Invalid ECG amplitude")
            val mv = value.toFloat()
            if (!value.isFinite() || !mv.isFinite()) {
                throw EcgParseException("ECG amplitude must be finite")
            }
            val hr = parseHeartRate(cols.getOrNull(2)?.trim())
            if (samples.size >= MAX_SAMPLES) {
                throw EcgParseException("ECG contains more than $MAX_SAMPLES samples")
            }
            samples.add(EcgSample(rel, mv, hr))
            previousRelMs = rel
        }
        if (samples.isEmpty()) {
            throw EcgParseException("No ECG samples")
        }
        return summarize(
            sessionId = sessionId,
            srHz = srHz,
            unit = "mV",
            tsStartMs = tsStart,
            wrist = wrist,
            signFactor = signFactor,
            polarityNormalized = polarityNormalized,
            watchInfo = watchInfo,
            samples = samples,
        )
    }

    private fun parseHeartRate(token: String?): Int? {
        if (token.isNullOrEmpty() || token.equals("NaN", ignoreCase = true)) return null
        return token.toIntOrNull()?.takeIf { it in MIN_HR_BPM..MAX_HR_BPM }
    }

    private fun readBoundedLine(reader: BufferedReader): String? {
        val line = StringBuilder(128)
        while (true) {
            val next = reader.read()
            if (next < 0) return line.takeIf { it.isNotEmpty() }?.toString()
            if (next == '\n'.code || next == '\r'.code) return line.toString()
            if (line.length >= MAX_LINE_CHARS) {
                throw EcgParseException("ECG line exceeds $MAX_LINE_CHARS characters")
            }
            line.append(next.toChar())
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

    /** Strict, dependency-free JSON object reader so the parser also runs in JVM unit tests. */
    internal class MetaJson(raw: String) {
        private val values: Map<String, JsonValue> = JsonParser(raw).parse()

        init {
            rejectAliasPair("signFactor", "sign_factor")
            rejectAliasPair("polarityNormalized", "polarity_normalized")
        }

        fun string(key: String, default: String): String = when (val value = values[key]) {
            null -> default
            is JsonValue.StringValue -> value.value
            else -> throw EcgParseException("Invalid $key metadata")
        }

        fun int(key: String, default: Int): Int = when (val value = values[key]) {
            null -> default
            is JsonValue.NumberValue -> value.token.toIntOrNull()
                ?: throw EcgParseException("Invalid $key metadata")
            else -> throw EcgParseException("Invalid $key metadata")
        }

        fun long(key: String, default: Long): Long = when (val value = values[key]) {
            null -> default
            is JsonValue.NumberValue -> value.token.toLongOrNull()
                ?: throw EcgParseException("Invalid $key metadata")
            else -> throw EcgParseException("Invalid $key metadata")
        }

        fun bool(key: String, default: Boolean): Boolean = when (val value = values[key]) {
            null -> default
            is JsonValue.BooleanValue -> value.value
            else -> throw EcgParseException("Invalid $key metadata")
        }

        private fun rejectAliasPair(first: String, second: String) {
            if (values.containsKey(first) && values.containsKey(second)) {
                throw EcgParseException("Duplicate reserved metadata keys: $first and $second")
            }
        }

        private sealed interface JsonValue {
            data class StringValue(val value: String) : JsonValue
            data class NumberValue(val token: String) : JsonValue
            data class BooleanValue(val value: Boolean) : JsonValue
            data object NullValue : JsonValue
            data object ContainerValue : JsonValue
        }

        private class JsonParser(private val source: String) {
            private var index = 0

            fun parse(): Map<String, JsonValue> {
                if (source.length > MAX_LINE_CHARS) fail("metadata is too long")
                skipWhitespace()
                val result = parseObject(depth = 0)
                skipWhitespace()
                if (index != source.length) fail("unexpected trailing content")
                return result
            }

            private fun parseObject(depth: Int): Map<String, JsonValue> {
                checkDepth(depth)
                expect('{')
                skipWhitespace()
                val result = LinkedHashMap<String, JsonValue>()
                if (consume('}')) return result
                while (true) {
                    skipWhitespace()
                    if (peek() != '"') fail("object key must be a string")
                    val key = parseString()
                    if (result.containsKey(key)) {
                        throw EcgParseException("Duplicate metadata key: $key")
                    }
                    skipWhitespace()
                    expect(':')
                    skipWhitespace()
                    result[key] = parseValue(depth + 1)
                    skipWhitespace()
                    when {
                        consume('}') -> return result
                        consume(',') -> Unit
                        else -> fail("expected ',' or '}'")
                    }
                }
            }

            private fun parseArray(depth: Int) {
                checkDepth(depth)
                expect('[')
                skipWhitespace()
                if (consume(']')) return
                while (true) {
                    parseValue(depth + 1)
                    skipWhitespace()
                    when {
                        consume(']') -> return
                        consume(',') -> {
                            skipWhitespace()
                        }
                        else -> fail("expected ',' or ']'")
                    }
                }
            }

            private fun parseValue(depth: Int): JsonValue {
                checkDepth(depth)
                return when (peek()) {
                    '"' -> JsonValue.StringValue(parseString())
                    '{' -> {
                        parseObject(depth)
                        JsonValue.ContainerValue
                    }
                    '[' -> {
                        parseArray(depth)
                        JsonValue.ContainerValue
                    }
                    't' -> {
                        expectLiteral("true")
                        JsonValue.BooleanValue(true)
                    }
                    'f' -> {
                        expectLiteral("false")
                        JsonValue.BooleanValue(false)
                    }
                    'n' -> {
                        expectLiteral("null")
                        JsonValue.NullValue
                    }
                    '-', in '0'..'9' -> JsonValue.NumberValue(parseNumber())
                    else -> fail("invalid JSON value")
                }
            }

            private fun parseString(): String {
                expect('"')
                val result = StringBuilder()
                while (index < source.length) {
                    val char = source[index++]
                    when {
                        char == '"' -> return result.toString()
                        char == '\\' -> {
                            if (index >= source.length) fail("unterminated string escape")
                            when (val escaped = source[index++]) {
                                '"', '\\', '/' -> result.append(escaped)
                                'b' -> result.append('\b')
                                'f' -> result.append('\u000c')
                                'n' -> result.append('\n')
                                'r' -> result.append('\r')
                                't' -> result.append('\t')
                                'u' -> result.append(parseUnicodeEscape())
                                else -> fail("invalid string escape")
                            }
                        }
                        char.code < 0x20 -> fail("unescaped control character")
                        else -> result.append(char)
                    }
                }
                fail("unterminated string")
            }

            private fun parseUnicodeEscape(): Char {
                if (index + 4 > source.length) fail("incomplete unicode escape")
                val token = source.substring(index, index + 4)
                val codePoint = token.toIntOrNull(16) ?: fail("invalid unicode escape")
                index += 4
                return codePoint.toChar()
            }

            private fun parseNumber(): String {
                val start = index
                consume('-')
                when (peek()) {
                    '0' -> {
                        index++
                        if (peek() in '0'..'9') fail("number has a leading zero")
                    }
                    in '1'..'9' -> while (peek() in '0'..'9') index++
                    else -> fail("invalid number")
                }
                if (consume('.')) {
                    if (peek() !in '0'..'9') fail("invalid number fraction")
                    while (peek() in '0'..'9') index++
                }
                if (peek() == 'e' || peek() == 'E') {
                    index++
                    if (peek() == '+' || peek() == '-') index++
                    if (peek() !in '0'..'9') fail("invalid number exponent")
                    while (peek() in '0'..'9') index++
                }
                return source.substring(start, index)
            }

            private fun expectLiteral(literal: String) {
                if (!source.regionMatches(index, literal, 0, literal.length)) {
                    fail("invalid literal")
                }
                index += literal.length
            }

            private fun expect(expected: Char) {
                if (!consume(expected)) fail("expected '$expected'")
            }

            private fun consume(expected: Char): Boolean {
                if (peek() != expected) return false
                index++
                return true
            }

            private fun peek(): Char = source.getOrNull(index) ?: '\u0000'

            private fun skipWhitespace() {
                while (peek() == ' ' || peek() == '\t' || peek() == '\r' || peek() == '\n') {
                    index++
                }
            }

            private fun checkDepth(depth: Int) {
                if (depth > MAX_JSON_DEPTH) fail("metadata nesting is too deep")
            }

            private fun fail(message: String): Nothing {
                throw EcgParseException("Malformed ECG metadata: $message at character $index")
            }
        }
    }

    private fun parseWrist(raw: String): Wrist {
        return when (raw.trim().uppercase(Locale.US)) {
            "LEFT", "L" -> Wrist.LEFT
            "RIGHT", "R" -> Wrist.RIGHT
            else -> Wrist.UNKNOWN
        }
    }

    private class LimitedInputStream(
        input: InputStream,
        private val maxBytes: Long,
        private val description: String,
    ) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            if (count >= maxBytes) {
                val extra = super.read()
                if (extra < 0) return -1
                throw EcgParseException("$description exceeds $maxBytes bytes")
            }
            val value = super.read()
            if (value >= 0) count++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (count >= maxBytes) return read().let { if (it < 0) -1 else 1 }
            val allowed = minOf(length.toLong(), maxBytes - count).toInt()
            val read = super.read(buffer, offset, allowed)
            if (read > 0) count += read
            return read
        }

        override fun skip(byteCount: Long): Long {
            if (byteCount <= 0L) return 0L
            val buffer = ByteArray(minOf(8_192L, byteCount).toInt())
            var skipped = 0L
            while (skipped < byteCount) {
                val read = read(buffer, 0, minOf(buffer.size.toLong(), byteCount - skipped).toInt())
                if (read < 0) break
                skipped += read
            }
            return skipped
        }
    }

    private val GZIP_MAGIC = byteArrayOf(0x1f, 0x8b.toByte())
}

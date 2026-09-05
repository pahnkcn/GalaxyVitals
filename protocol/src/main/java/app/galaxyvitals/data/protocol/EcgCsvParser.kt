package app.galaxyvitals.data.protocol

import app.galaxyvitals.data.protocol.csv.EcgCsvSource
import app.galaxyvitals.data.protocol.csv.MAX_LINE_CHARS
import app.galaxyvitals.data.protocol.csv.MetaJson
import app.galaxyvitals.data.protocol.csv.readEcgMetaHeader
import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.domain.CaptureSource
import app.galaxyvitals.domain.LiveBpmObservation
import app.galaxyvitals.domain.TimingTrust
import app.galaxyvitals.domain.Wrist
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
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
    val schemaVersion: Int = 1,
    val captureSource: CaptureSource = CaptureSource.LEGACY,
    val timingTrust: TimingTrust = TimingTrust.ASSUMED,
    val effectiveSrHz: Double = srHz.toDouble(),
    val sensorStartMs: Long? = null,
    val declaredSampleCount: Int? = null,
    val gapCount: Int = 0,
    val missingSampleCount: Int = 0,
    val sequenceGapCount: Int = 0,
    val contactLossCount: Int = 0,
    val clippedSampleCount: Int = 0,
    val acquisitionFlags: Int = 0,
    val minThresholdMv: Float? = null,
    val maxThresholdMv: Float? = null,
    val analysisClockSource: String? = null,
    val rawClockSource: String? = null,
    val rawTimingTrust: TimingTrust? = null,
    val rawSensorDurationMs: Long? = null,
    val listenerDurationMs: Long? = null,
    val missingSampleCountKnown: Boolean = true,
    val repeatedTimestampCount: Int = 0,
    val batchCount: Int = 0,
    val sensorSdk: String? = null,
    val sensorAarSha256: String? = null,
    val bpmObservations: List<LiveBpmObservation> = emptyList(),
    val liveBpmMedian: Double? = null,
    val liveBpmMin: Double? = null,
    val liveBpmMax: Double? = null,
    val liveBpmReliableCoveragePct: Double = 0.0,
    val liveBpmAlgorithmId: String? = null,
    val liveBpmObservationCount: Int = 0,
)

class EcgParseException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reader for the gzip-CSV recording contract.
 *
 * The pieces that are their own problem live beside this file in `csv/`: the
 * strict metadata JSON reader, the bounded stream, the gzip sniffing, and the
 * per-schema-version header rules. What remains here is the shape of a
 * recording - how rows are read and what a parsed file summarises to.
 */
object EcgCsvParser {

    const val MAX_COMPRESSED_BYTES = 8L * 1024L * 1024L
    const val MAX_UNCOMPRESSED_BYTES = 16L * 1024L * 1024L
    const val MAX_SAMPLES = 30_000
    const val MAX_DURATION_MS = 120_000L

    private const val MIN_HR_BPM = 20
    private const val MAX_HR_BPM = 300

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

    /** Reads only the bounded metadata line and deliberately returns unknown tokens such as DEMO. */
    fun peekCaptureSourceToken(file: File): String? {
        if (file.length() > MAX_COMPRESSED_BYTES) return null
        return runCatching {
            FileInputStream(file).use(::peekCaptureSourceToken)
        }.getOrNull()
    }

    /** Reads only the bounded metadata line and deliberately returns unknown tokens such as DEMO. */
    fun peekCaptureSourceToken(bytes: ByteArray): String? {
        if (bytes.size.toLong() > MAX_COMPRESSED_BYTES) return null
        return runCatching {
            ByteArrayInputStream(bytes).use(::peekCaptureSourceToken)
        }.getOrNull()
    }

    private fun peekCaptureSourceToken(input: InputStream): String? {
        val sniffed = EcgCsvSource.sniffGzip(input, MAX_COMPRESSED_BYTES)
        val decoded = if (sniffed.gzip) GZIPInputStream(sniffed.stream) else sniffed.stream
        val boundedDecoded = EcgCsvSource.bound(
            decoded,
            MAX_UNCOMPRESSED_BYTES,
            EcgCsvSource.UNCOMPRESSED_DESCRIPTION,
        )
        BufferedReader(InputStreamReader(boundedDecoded, StandardCharsets.UTF_8)).use { reader ->
            val first = readBoundedLine(reader) ?: return null
            if (!first.startsWith("#meta=")) return null
            val meta = MetaJson(first.substring(6).trim())
            if (!meta.has("capture_source")) return null
            return meta.string("capture_source", "").trim()
                .takeIf(String::isNotEmpty)
                ?.uppercase(Locale.US)
        }
    }

    fun isGzip(bytes: ByteArray): Boolean = EcgCsvSource.looksGzipped(bytes)

    fun parseAutoStream(input: InputStream, sessionIdHint: String): ParsedEcgFile {
        return try {
            val sniffed = EcgCsvSource.sniffGzip(input, MAX_COMPRESSED_BYTES)
            parseDecodedStream(sniffed.stream, sniffed.gzip, sessionIdHint)
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
        val boundedRaw = EcgCsvSource.bound(
            input = input,
            maxBytes = MAX_COMPRESSED_BYTES,
            description = EcgCsvSource.COMPRESSED_DESCRIPTION,
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
        val boundedDecoded = EcgCsvSource.bound(
            input = decoded,
            maxBytes = MAX_UNCOMPRESSED_BYTES,
            description = EcgCsvSource.UNCOMPRESSED_DESCRIPTION,
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

    /**
     * Reads the metadata line, then the sample rows it describes.
     *
     * The per-version metadata rules live in [readEcgMetaHeader]; what stays
     * here is the row grammar, which reads the same three ways the header says
     * it may be shaped. The handful of header values the row loop itself
     * reasons about are bound to locals so the loop keeps reading as one thing.
     */
    private fun parseReader(reader: BufferedReader, sessionId: String): ParsedEcgFile {
        val first = readBoundedLine(reader) ?: throw EcgParseException("Empty file")
        if (!first.startsWith("#meta=")) {
            throw EcgParseException("Missing #meta header")
        }
        val header = readEcgMetaHeader(MetaJson(first.substring(6).trim()))
        val schemaVersion = header.schemaVersion
        val srHz = header.srHz
        val effectiveSrHz = header.effectiveSrHz
        val declaredSampleCount = header.declaredSampleCount
        val declaredDurationMs = header.declaredDurationMs

        val samples = ArrayList<EcgSample>(minOf(MAX_SAMPLES, srHz * 30))
        val bpmObservations = ArrayList<LiveBpmObservation>(LiveBpmSummarizer.MAX_OBSERVATIONS)
        var firstRelMs: Long? = null
        var previousRelMs: Long? = null
        var previousRawTs: Long? = null
        while (true) {
            val rawLine = readBoundedLine(reader) ?: break
            if (rawLine.isBlank()) continue
            if (rawLine.startsWith("#bpm=")) {
                parseBpmLine(rawLine.substring(5).trim(), bpmObservations)
                continue
            }
            if (rawLine.startsWith("#")) continue
            if (rawLine.startsWith("rel_ms") || rawLine.startsWith("timestamp_ms")) continue
            val cols = rawLine.split(',')
            val requiredColumns = when (schemaVersion) {
                3 -> 9
                2 -> 4
                else -> 2
            }
            if (cols.size < requiredColumns) throw EcgParseException("Invalid ECG sample row")

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

            val sampleIndex = if (schemaVersion >= 2) {
                cols[1].trim().toIntOrNull()
                    ?: throw EcgParseException("Invalid ECG sample index")
            } else {
                samples.size
            }
            if (sampleIndex != samples.size) {
                throw EcgParseException("ECG sample indices must start at zero and be contiguous")
            }
            if (schemaVersion == 3) {
                val expectedRel = sampleIndex.toLong() * 1000L / srHz
                if (rel != expectedRel) {
                    throw EcgParseException("ECG reconstructed timestamps must equal sample_index × period")
                }
            }
            val valueColumn = if (schemaVersion >= 2) 2 else 1
            val value = cols[valueColumn].trim().toDoubleOrNull()
                ?: throw EcgParseException("Invalid ECG amplitude")
            val mv = value.toFloat()
            if (!value.isFinite() || !mv.isFinite()) {
                throw EcgParseException("ECG amplitude must be finite")
            }
            val flags = if (schemaVersion >= 2) {
                cols[3].trim().toIntOrNull()?.takeIf { it >= 0 }
                    ?: throw EcgParseException("Invalid ECG sample flags")
            } else {
                0
            }
            val hrColumn = if (schemaVersion >= 2) 4 else 2
            val hr = parseHeartRate(cols.getOrNull(hrColumn)?.trim())
            val rawTs = if (schemaVersion == 3) {
                cols[5].trim().toLongOrNull()?.takeIf { it >= 0L }
                    ?: throw EcgParseException("Invalid ECG raw sensor timestamp")
            } else {
                null
            }
            if (rawTs != null && previousRawTs?.let { rawTs < it } == true) {
                throw EcgParseException("ECG raw timestamps must be nonnegative and nondecreasing")
            }
            val batchSequence = if (schemaVersion == 3) {
                cols[6].trim().toIntOrNull()?.takeIf { it in 0..255 }
                    ?: throw EcgParseException("Invalid ECG batch sequence")
            } else {
                null
            }
            val batchOffset = if (schemaVersion == 3) {
                cols[7].trim().toIntOrNull()?.takeIf { it >= 0 }
                    ?: throw EcgParseException("Invalid ECG batch sample offset")
            } else {
                null
            }
            val batchSize = if (schemaVersion == 3) {
                cols[8].trim().toIntOrNull()?.takeIf { it >= 1 }
                    ?: throw EcgParseException("Invalid ECG batch size")
            } else {
                null
            }
            if (batchOffset != null && batchSize != null && batchOffset >= batchSize) {
                throw EcgParseException("ECG batch sample offset must be inside the batch")
            }
            if (samples.size >= MAX_SAMPLES) {
                throw EcgParseException("ECG contains more than $MAX_SAMPLES samples")
            }
            samples.add(
                EcgSample(
                    relMs = rel,
                    valueMv = mv,
                    hrBpm = hr,
                    sampleIndex = sampleIndex,
                    flags = flags,
                    sensorTimestampMsRaw = rawTs,
                    batchSequence = batchSequence,
                    batchSampleOffset = batchOffset,
                    batchSize = batchSize,
                ),
            )
            previousRelMs = rel
            if (rawTs != null) previousRawTs = rawTs
        }
        if (samples.isEmpty()) {
            throw EcgParseException("No ECG samples")
        }
        LiveBpmSummarizer.parseValid(bpmObservations)
        val actualDurationMs = samples.last().relMs - samples.first().relMs
        if (declaredSampleCount != null && declaredSampleCount != samples.size) {
            throw EcgParseException("ECG sample count metadata does not match rows")
        }
        if (declaredDurationMs != null && kotlin.math.abs(declaredDurationMs - actualDurationMs) > 2L) {
            throw EcgParseException("ECG duration metadata does not match sensor timestamps")
        }
        // Schema v3 stores the unmodified Samsung DataPoint timestamps, so the
        // real sample rate is measurable instead of assumed. `effective_sr_hz`
        // in the metadata is derived from the reconstructed `sample_index x 2`
        // grid and therefore always restates the nominal rate; on Galaxy Watch
        // hardware the true rate is near 501.67 Hz, which biases every interval
        // built on the nominal grid by ~0.33%. Prefer the measured value.
        val measuredSrHz = if (schemaVersion >= 3) {
            EcgSignalChain.estimateSampleRateHz(samples, srHz)
        } else {
            effectiveSrHz
        }
        return summarize(
            sessionId = sessionId,
            srHz = srHz,
            unit = "mV",
            tsStartMs = header.tsStart,
            wrist = header.wrist,
            signFactor = header.signFactor,
            polarityNormalized = header.polarityNormalized,
            watchInfo = header.watchInfo,
            samples = samples,
            schemaVersion = schemaVersion,
            captureSource = header.captureSource,
            timingTrust = header.timingTrust,
            effectiveSrHz = measuredSrHz,
            sensorStartMs = header.sensorStartMs,
            declaredSampleCount = declaredSampleCount,
            gapCount = header.gapCount,
            missingSampleCount = header.missingSampleCount,
            sequenceGapCount = header.sequenceGapCount,
            contactLossCount = header.contactLossCount,
            clippedSampleCount = header.clippedSampleCount,
            acquisitionFlags = header.acquisitionFlags,
            minThresholdMv = header.minThresholdMv,
            maxThresholdMv = header.maxThresholdMv,
            analysisClockSource = header.analysisClockSource,
            rawClockSource = header.rawClockSource,
            rawTimingTrust = header.rawTimingTrust,
            rawSensorDurationMs = header.rawSensorDurationMs,
            listenerDurationMs = header.listenerDurationMs,
            missingSampleCountKnown = header.missingSampleCountKnown,
            repeatedTimestampCount = header.repeatedTimestampCount,
            batchCount = header.batchCount,
            sensorSdk = header.sensorSdk,
            sensorAarSha256 = header.sensorAarSha256,
            bpmObservations = bpmObservations,
            liveBpmAlgorithmId = header.liveBpmAlgorithmId,
        )
    }

    private fun parseBpmLine(raw: String, observations: ArrayList<LiveBpmObservation>) {
        if (observations.size >= LiveBpmSummarizer.MAX_OBSERVATIONS) {
            throw EcgParseException("ECG contains more than ${LiveBpmSummarizer.MAX_OBSERVATIONS} live BPM observations")
        }
        val json = MetaJson(raw)
        val id = json.int("id", -1)
        if (id != observations.size) {
            throw EcgParseException("Live BPM observation ids must start at zero and be contiguous")
        }
        observations += LiveBpmObservation(
            atSampleIndex = json.long("at_sample_index", -1L).also { index ->
                if (index < 0L) throw EcgParseException("Invalid live BPM sample index")
            },
            observedCaptureElapsedMs = json.long("observed_capture_elapsed_ms", -1L).also { elapsed ->
                if (elapsed < 0L) throw EcgParseException("Invalid live BPM elapsed time")
            },
            status = json.string("status", "").also { status ->
                if (status.isBlank()) throw EcgParseException("Invalid live BPM status")
            },
            displayedBpm = json.nullableDouble("displayed_bpm"),
            rawBpm = json.nullableDouble("raw_bpm"),
            source = json.nullableString("source"),
            bSqi = json.nullableDouble("b_sqi"),
            rrCount = json.nullableInt("rr_count"),
            estimateAgeMs = json.long("estimate_age_ms", 0L).also { age ->
                if (age < 0L) throw EcgParseException("Invalid live BPM estimate age")
            },
            reasonCode = json.nullableString("reason_code"),
            sensorTimestampMs = json.nullableLong("sensor_timestamp_ms"),
            sensorStatus = json.nullableInt("sensor_status"),
            ibiMs = json.intList("ibi_ms"),
            ibiStatus = json.intList("ibi_status"),
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
        schemaVersion: Int = 1,
        captureSource: CaptureSource = CaptureSource.LEGACY,
        timingTrust: TimingTrust = TimingTrust.ASSUMED,
        effectiveSrHz: Double = srHz.toDouble(),
        sensorStartMs: Long? = null,
        declaredSampleCount: Int? = null,
        gapCount: Int = 0,
        missingSampleCount: Int = 0,
        sequenceGapCount: Int = 0,
        contactLossCount: Int = 0,
        clippedSampleCount: Int = 0,
        acquisitionFlags: Int = 0,
        minThresholdMv: Float? = null,
        maxThresholdMv: Float? = null,
        analysisClockSource: String? = null,
        rawClockSource: String? = null,
        rawTimingTrust: TimingTrust? = null,
        rawSensorDurationMs: Long? = null,
        listenerDurationMs: Long? = null,
        missingSampleCountKnown: Boolean = true,
        repeatedTimestampCount: Int = 0,
        batchCount: Int = 0,
        sensorSdk: String? = null,
        sensorAarSha256: String? = null,
        bpmObservations: List<LiveBpmObservation> = emptyList(),
        liveBpmAlgorithmId: String? = null,
    ): ParsedEcgFile {
        val hrs = samples.mapNotNull { it.hrBpm }.sorted()
        val hrMedian = if (hrs.isEmpty()) null else EcgStats.medianOfSorted(hrs)
        val durationSec = if (samples.size <= 1) {
            0.0
        } else {
            (samples.last().relMs - samples.first().relMs) / 1000.0
        }
        val usable = samples.count { abs(it.valueMv) > 1e-6f }
        val periodMs = if (srHz > 0) 1000L / srHz else EcgWearContract.SAMPLE_PERIOD_MS
        val liveSummary = LiveBpmSummarizer.summarize(
            bpmObservations,
            sessionDurationMs = samples.size * periodMs,
        )
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
            schemaVersion = schemaVersion,
            captureSource = captureSource,
            timingTrust = timingTrust,
            effectiveSrHz = effectiveSrHz,
            sensorStartMs = sensorStartMs,
            declaredSampleCount = declaredSampleCount,
            gapCount = gapCount,
            missingSampleCount = missingSampleCount,
            sequenceGapCount = sequenceGapCount,
            contactLossCount = contactLossCount,
            clippedSampleCount = clippedSampleCount,
            acquisitionFlags = acquisitionFlags,
            minThresholdMv = minThresholdMv,
            maxThresholdMv = maxThresholdMv,
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
            bpmObservations = bpmObservations,
            liveBpmMedian = liveSummary.median,
            liveBpmMin = liveSummary.min,
            liveBpmMax = liveSummary.max,
            liveBpmReliableCoveragePct = liveSummary.reliableCoveragePct,
            liveBpmAlgorithmId = liveBpmAlgorithmId ?: liveSummary.algorithmId,
            liveBpmObservationCount = liveSummary.observationCount,
        )
    }

}

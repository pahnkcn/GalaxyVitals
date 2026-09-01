package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.domain.CaptureSource
import app.galaxyvitals.domain.LiveBpmObservation
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
        if (parsed.schemaVersion >= 3) {
            return encodeCaptureV3FromParsed(parsed)
        }
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

    fun encodeCaptureV3(
        wallStartMs: Long,
        sensorStartMs: Long,
        valuesMv: FloatArray,
        sampleFlags: IntArray,
        sensorTimestampsMsRaw: LongArray,
        batchSequence: IntArray,
        batchSampleOffset: IntArray,
        batchSize: IntArray,
        wrist: Wrist,
        signFactor: Int,
        watchInfo: String,
        captureSource: CaptureSource,
        bpmObservations: List<LiveBpmObservation> = emptyList(),
        listenerDurationMs: Long = 0L,
        gapCount: Int = 0,
        missingSampleCount: Int = 0,
        sequenceGapCount: Int = 0,
        contactLossCount: Int = 0,
        clippedSampleCount: Int = 0,
        acquisitionFlags: Int = 0,
        minThresholdMv: Float? = null,
        maxThresholdMv: Float? = null,
        repeatedTimestampCount: Int = 0,
        batchCount: Int = 0,
        rawTimingTrust: TimingTrust = TimingTrust.UNVERIFIED,
        sensorSdk: String? = null,
        sensorAarSha256: String? = null,
        liveBpmAlgorithmId: String? = LiveBpmSummarizer.ALGORITHM_ID,
        nominalSrHz: Int = EcgWearContract.DEFAULT_SR_HZ,
    ): ByteArray {
        require(valuesMv.isNotEmpty()) { "No ECG samples" }
        require(
            valuesMv.size == sampleFlags.size &&
                valuesMv.size == sensorTimestampsMsRaw.size &&
                valuesMv.size == batchSequence.size &&
                valuesMv.size == batchSampleOffset.size &&
                valuesMv.size == batchSize.size,
        ) { "ECG sample arrays must have equal sizes" }
        require(signFactor == -1 || signFactor == 1) { "Invalid ECG polarity sign factor" }
        require(captureSource != CaptureSource.LEGACY) { "Schema v3 requires an explicit capture source" }
        require(nominalSrHz == EcgWearContract.DEFAULT_SR_HZ) { "Schema v3 requires 500 Hz samples" }
        LiveBpmSummarizer.requireValid(bpmObservations)
        var previousRaw = -1L
        valuesMv.indices.forEach { index ->
            require(valuesMv[index].isFinite()) { "ECG amplitude must be finite" }
            require(sampleFlags[index] >= 0) { "ECG flags must be nonnegative" }
            val raw = sensorTimestampsMsRaw[index]
            require(raw >= 0L && (previousRaw < 0L || raw >= previousRaw)) {
                "ECG raw timestamps must be nonnegative and nondecreasing"
            }
            require(batchSequence[index] in 0..255) { "ECG batch sequence must be 0..255" }
            require(batchSampleOffset[index] >= 0) { "ECG batch sample offset must be nonnegative" }
            require(batchSize[index] >= 1) { "ECG batch size must be positive" }
            require(batchSampleOffset[index] < batchSize[index]) {
                "ECG batch sample offset must be inside the batch"
            }
            previousRaw = raw
        }
        val periodMs = EcgWearContract.SAMPLE_PERIOD_MS
        val durationMs = (valuesMv.size - 1L) * periodMs
        // `rel_ms` is the reconstructed `sample_index x period` grid, so deriving
        // the rate from it can only ever restate the nominal 500 Hz. The raw
        // Samsung stamps are the only record of the real clock - near 501.67 Hz
        // on this hardware - so fit them instead.
        val effectiveSrHz = EcgSignalChain.estimateSampleRateHz(sensorTimestampsMsRaw, nominalSrHz)
        val rawSensorDurationMs = sensorTimestampsMsRaw.last() - sensorTimestampsMsRaw.first()
        val sessionDurationMs = valuesMv.size * periodMs
        val summary = LiveBpmSummarizer.summarize(bpmObservations, sessionDurationMs)
        val resolvedSdk = sensorSdk ?: extractJsonString(watchInfo, "sensorSdk")
        val resolvedAar = sensorAarSha256 ?: extractJsonString(watchInfo, "sensorAarSha256")
        val algorithmId = liveBpmAlgorithmId ?: summary.algorithmId
        val body = buildString(valuesMv.size * 48 + bpmObservations.size * 160) {
            append(
                metaLineV3(
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
                    rawTimingTrust = rawTimingTrust,
                    rawSensorDurationMs = rawSensorDurationMs,
                    listenerDurationMs = listenerDurationMs,
                    repeatedTimestampCount = repeatedTimestampCount,
                    batchCount = batchCount,
                    sensorSdk = resolvedSdk,
                    sensorAarSha256 = resolvedAar,
                    liveBpmAlgorithmId = algorithmId,
                    liveBpmSummary = summary,
                ),
            )
            append("rel_ms,sample_index,ecg_raw_mv,flags,hr_bpm,sensor_timestamp_ms_raw,batch_sequence,batch_sample_offset,batch_size\n")
            valuesMv.indices.forEach { index ->
                append(index * periodMs).append(',')
                append(index).append(',')
                append(valuesMv[index]).append(',')
                append(sampleFlags[index]).append(',')
                append(',')
                append(sensorTimestampsMsRaw[index]).append(',')
                append(batchSequence[index]).append(',')
                append(batchSampleOffset[index]).append(',')
                append(batchSize[index]).append('\n')
            }
            bpmObservations.forEachIndexed { id, observation ->
                appendBpmLine(this, id, observation)
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

    private fun encodeCaptureV3FromParsed(parsed: ParsedEcgFile): ByteArray {
        val samples = parsed.samples
        return encodeCaptureV3(
            wallStartMs = parsed.tsStartMs,
            sensorStartMs = parsed.sensorStartMs ?: samples.first().sensorTimestampMsRaw ?: 0L,
            valuesMv = FloatArray(samples.size) { samples[it].valueMv },
            sampleFlags = IntArray(samples.size) { samples[it].flags },
            sensorTimestampsMsRaw = LongArray(samples.size) { index ->
                samples[index].sensorTimestampMsRaw
                    ?: throw IllegalArgumentException("Schema v3 samples require raw sensor timestamps")
            },
            batchSequence = IntArray(samples.size) { index ->
                samples[index].batchSequence
                    ?: throw IllegalArgumentException("Schema v3 samples require batch sequence")
            },
            batchSampleOffset = IntArray(samples.size) { index ->
                samples[index].batchSampleOffset
                    ?: throw IllegalArgumentException("Schema v3 samples require batch sample offset")
            },
            batchSize = IntArray(samples.size) { index ->
                samples[index].batchSize
                    ?: throw IllegalArgumentException("Schema v3 samples require batch size")
            },
            wrist = parsed.wrist,
            signFactor = parsed.signFactor,
            watchInfo = parsed.watchInfo,
            captureSource = parsed.captureSource,
            bpmObservations = parsed.bpmObservations,
            listenerDurationMs = parsed.listenerDurationMs ?: 0L,
            gapCount = parsed.gapCount,
            missingSampleCount = parsed.missingSampleCount,
            sequenceGapCount = parsed.sequenceGapCount,
            contactLossCount = parsed.contactLossCount,
            clippedSampleCount = parsed.clippedSampleCount,
            acquisitionFlags = parsed.acquisitionFlags,
            minThresholdMv = parsed.minThresholdMv,
            maxThresholdMv = parsed.maxThresholdMv,
            repeatedTimestampCount = parsed.repeatedTimestampCount,
            batchCount = parsed.batchCount,
            rawTimingTrust = parsed.rawTimingTrust ?: TimingTrust.UNVERIFIED,
            sensorSdk = parsed.sensorSdk,
            sensorAarSha256 = parsed.sensorAarSha256,
            liveBpmAlgorithmId = parsed.liveBpmAlgorithmId ?: LiveBpmSummarizer.ALGORITHM_ID,
            nominalSrHz = parsed.srHz,
        )
    }

    private fun metaLineV3(
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
        liveBpmSummary: app.galaxyvitals.domain.LiveBpmSummary,
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

    private fun appendBpmLine(out: StringBuilder, id: Int, observation: LiveBpmObservation) {
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

    private fun extractJsonString(blob: String, key: String): String? {
        val needle = "\"$key\":\""
        val start = blob.indexOf(needle)
        if (start < 0) return null
        val from = start + needle.length
        val end = blob.indexOf('"', from)
        if (end <= from) return null
        return blob.substring(from, end).ifBlank { null }
    }
}

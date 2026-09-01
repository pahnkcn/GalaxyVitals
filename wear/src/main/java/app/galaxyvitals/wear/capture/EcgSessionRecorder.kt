package app.galaxyvitals.wear.capture

import app.galaxyvitals.data.protocol.EcgCsvWriter
import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.data.protocol.LiveBpmSummarizer
import app.galaxyvitals.domain.CaptureSource
import app.galaxyvitals.domain.EcgSampleFlags
import app.galaxyvitals.domain.TimingTrust
import app.galaxyvitals.domain.Wrist
import app.galaxyvitals.wear.sensors.EcgBatch
import kotlin.math.abs

class EcgCaptureException(message: String) : IllegalStateException(message)

class EcgSessionRecorder {
    private val lock = Any()
    private var recording = false
    var sessionId: String = ""
        private set
    private var wallStartMs = 0L
    private var wrist = Wrist.LEFT
    private var signFactor = 1
    private val values = FloatArray(MAX_SAMPLES)
    private val sensorTimestampsMs = LongArray(MAX_SAMPLES)
    private val flags = IntArray(MAX_SAMPLES)
    private val batchSequences = IntArray(MAX_SAMPLES)
    private val batchSampleOffsets = IntArray(MAX_SAMPLES)
    private val batchSizes = IntArray(MAX_SAMPLES)
    private var size = 0
    private var sensorStartMs = -1L
    private var previousTimestampMs = -1L
    private var previousBatchSize = 0
    private var previousSequence = -1
    private var gapCount = 0
    private var missingSampleCount = 0
    private var sequenceGapCount = 0
    private var contactLossCount = 0
    private var clippedSampleCount = 0
    private var acquisitionFlags = 0
    private var repeatedTimestampCount = 0
    private var batchCount = 0
    private var minThresholdMv: Float? = null
    private var maxThresholdMv: Float? = null
    private val bpmObservations = ArrayList<LiveBpmObservation>(LiveBpmSummarizer.MAX_OBSERVATIONS)

    val isRecording: Boolean get() = synchronized(lock) { recording }
    val sampleCount: Int get() = synchronized(lock) { size }

    fun begin(
        sessionId: String,
        wrist: Wrist,
        signFactor: Int,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        require(signFactor == -1 || signFactor == 1)
        synchronized(lock) {
            this.sessionId = EcgWearContract.requireSessionId(sessionId)
            this.wrist = wrist
            this.signFactor = signFactor
            wallStartMs = nowMs
            size = 0
            sensorStartMs = -1L
            previousTimestampMs = -1L
            previousBatchSize = 0
            previousSequence = -1
            gapCount = 0
            missingSampleCount = 0
            sequenceGapCount = 0
            contactLossCount = 0
            clippedSampleCount = 0
            acquisitionFlags = 0
            repeatedTimestampCount = 0
            batchCount = 0
            minThresholdMv = null
            maxThresholdMv = null
            bpmObservations.clear()
            recording = true
        }
    }

    fun addEcgAtomically(batch: EcgBatch) = addEcg(batch)

    fun addBpmObservation(status: String) {
        addBpmObservation(
            LiveBpmObservation(
                atSampleIndex = synchronized(lock) { size.toLong() },
                observedCaptureElapsedMs = 0L,
                status = status,
            ),
        )
    }

    fun addBpmObservation(observation: LiveBpmObservation) {
        synchronized(lock) {
            if (!recording) return
            if (bpmObservations.size >= LiveBpmSummarizer.MAX_OBSERVATIONS) return
            if (
                bpmObservations.isNotEmpty() &&
                observation.observedCaptureElapsedMs < bpmObservations.last().observedCaptureElapsedMs
            ) {
                return
            }
            if (LiveBpmSummarizer.validationError(listOf(observation)) != null) return
            bpmObservations += observation
        }
    }

    fun liveBpmObservations(): List<LiveBpmObservation> = synchronized(lock) { bpmObservations.toList() }

    fun addEcg(batch: EcgBatch) {
        synchronized(lock) {
            if (!recording || batch.samplesMv.isEmpty()) return
            val remaining = EXPECTED_SAMPLES - size
            if (remaining <= 0) return
            if (size + batch.samplesMv.size > MAX_SAMPLES) {
                throw EcgCaptureException("ECG sample buffer overflow")
            }
            val storeCount = minOf(batch.samplesMv.size, remaining)
            if (!batch.contactValid) {
                throw EcgCaptureException("ECG contact was lost")
            }
            if (previousSequence >= 0) {
                val expected = (previousSequence + 1) and 0xff
                if (batch.sequence != expected) {
                    throw EcgCaptureException("ECG sequence gap or duplicate")
                }
            }
            val pendingFlags = IntArray(storeCount)
            var pendingRepeats = 0
            var pendingGaps = 0
            var previousRaw = previousTimestampMs
            for (index in 0 until storeCount) {
                val sample = batch.samplesMv[index]
                if (!sample.isFinite()) {
                    throw EcgCaptureException("ECG contains a non-finite sample")
                }
                val timestamp = batch.sensorTimestampsMs[index]
                if (timestamp < 0L || (previousRaw >= 0L && timestamp < previousRaw)) {
                    throw EcgCaptureException("ECG sensor timestamp reversed")
                }
                val sampleFlags = batch.sampleFlags[index]
                val clipped = sampleFlags and EcgSampleFlags.CLIPPED != 0 ||
                    batch.minThresholdMv?.let { sample < it } == true ||
                    batch.maxThresholdMv?.let { sample > it } == true
                if (clipped) {
                    throw EcgCaptureException("ECG reached the sensor saturation threshold")
                }
                if (previousRaw >= 0L) {
                    // A batch carries one timestamp for all of its samples, so
                    // the only real jump is the one at a batch boundary and it
                    // should be the whole previous batch long. Counting every
                    // boundary as a gap - which the old `delta > 2 ms` test did -
                    // made `gap_count` a batch counter (1499 on a clean 30 s
                    // capture) and useless as an artifact signal. Flag only a
                    // jump half a batch beyond what the samples in between
                    // account for.
                    val delta = timestamp - previousRaw
                    val expectedMs = if (index == 0) {
                        previousBatchSize * EXPECTED_PERIOD_MS
                    } else {
                        EXPECTED_PERIOD_MS
                    }
                    val limitMs = expectedMs + maxOf(EXPECTED_PERIOD_MS, expectedMs / 2L)
                    if (delta == 0L) {
                        pendingRepeats++
                    } else if (delta > limitMs) {
                        pendingGaps++
                    }
                }
                pendingFlags[index] = sampleFlags
                previousRaw = timestamp
            }
            val sourceSize = maxOf(batch.sourceBatchSize, batch.samplesMv.size)
            for (index in 0 until storeCount) {
                values[size] = batch.samplesMv[index]
                sensorTimestampsMs[size] = batch.sensorTimestampsMs[index]
                flags[size] = pendingFlags[index]
                batchSequences[size] = batch.sequence
                batchSampleOffsets[size] = index
                batchSizes[size] = sourceSize
                size++
            }
            if (sensorStartMs < 0L) sensorStartMs = batch.sensorTimestampsMs[0]
            previousTimestampMs = previousRaw
            previousBatchSize = storeCount
            previousSequence = batch.sequence
            repeatedTimestampCount += pendingRepeats
            gapCount += pendingGaps
            batchCount++
            minThresholdMv = batch.minThresholdMv ?: minThresholdMv
            maxThresholdMv = batch.maxThresholdMv ?: maxThresholdMv
        }
    }

    /** Compatibility helper for deterministic JVM tests; production uses timestamped batches. */
    @Suppress("UNUSED_PARAMETER")
    fun addEcg(batch: FloatArray, applySign: Boolean = false) {
        val firstTimestamp = synchronized(lock) {
            if (previousTimestampMs >= 0L) previousTimestampMs + EXPECTED_PERIOD_MS else 1_000L
        }
        val sequence = synchronized(lock) { if (previousSequence < 0) 0 else (previousSequence + 1) and 0xff }
        addEcg(
            EcgBatch(
                samplesMv = batch,
                sensorTimestampsMs = LongArray(batch.size) { firstTimestamp + it * EXPECTED_PERIOD_MS },
                sequence = sequence,
                leadOff = 0,
                minThresholdMv = -5f,
                maxThresholdMv = 5f,
                sampleFlags = IntArray(batch.size),
            ),
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun addHr(epochMs: Long, bpm: Int) = Unit

    fun cancel() {
        synchronized(lock) {
            recording = false
            size = 0
            bpmObservations.clear()
        }
    }

    fun takeSnapshot(listenerDurationMs: Long? = null): EcgSessionSnapshot = synchronized(lock) {
        check(recording) { "No ECG recording is active" }
        check(size > 0) { "No ECG samples were recorded" }
        recording = false
        val reconstructedDurationMs = (size - 1L).coerceAtLeast(0L) * EXPECTED_PERIOD_MS
        EcgSessionSnapshot(
            sessionId = sessionId,
            wallStartMs = wallStartMs,
            sensorStartMs = sensorStartMs,
            values = values.copyOf(size),
            sensorTimestampsMs = sensorTimestampsMs.copyOf(size),
            flags = flags.copyOf(size),
            batchSequence = batchSequences.copyOf(size),
            batchSampleOffset = batchSampleOffsets.copyOf(size),
            batchSize = batchSizes.copyOf(size),
            wrist = wrist,
            signFactor = signFactor,
            gapCount = gapCount,
            missingSampleCount = missingSampleCount,
            sequenceGapCount = sequenceGapCount,
            contactLossCount = contactLossCount,
            clippedSampleCount = clippedSampleCount,
            acquisitionFlags = acquisitionFlags,
            minThresholdMv = minThresholdMv,
            maxThresholdMv = maxThresholdMv,
            repeatedTimestampCount = repeatedTimestampCount,
            batchCount = batchCount,
            bpmObservations = bpmObservations.toList(),
            listenerDurationMs = listenerDurationMs ?: reconstructedDurationMs,
        ).also { size = 0 }
    }

    fun finish(watchInfo: String): RecordedSession = finish(takeSnapshot(), watchInfo)
    fun finish(snapshot: EcgSessionSnapshot, watchInfo: String): RecordedSession = snapshot.encode(watchInfo)

    companion object {
        const val MAX_SAMPLES = 500 * 32
        const val EXPECTED_SAMPLES = 500 * 30
        const val EXPECTED_PERIOD_MS = 2L
        const val RATE_TOLERANCE_FRACTION = 0.01
    }
}

class EcgSessionSnapshot internal constructor(
    val sessionId: String,
    private val wallStartMs: Long,
    private val sensorStartMs: Long,
    private val values: FloatArray,
    private val sensorTimestampsMs: LongArray,
    private val flags: IntArray,
    private val batchSequence: IntArray,
    private val batchSampleOffset: IntArray,
    private val batchSize: IntArray,
    private val wrist: Wrist,
    private val signFactor: Int,
    private val gapCount: Int,
    private val missingSampleCount: Int,
    private val sequenceGapCount: Int,
    private val contactLossCount: Int,
    private val clippedSampleCount: Int,
    private val acquisitionFlags: Int,
    private val minThresholdMv: Float?,
    private val maxThresholdMv: Float?,
    private val repeatedTimestampCount: Int,
    private val batchCount: Int,
    private val bpmObservations: List<LiveBpmObservation>,
    private val listenerDurationMs: Long,
) {
    val nSamples: Int get() = values.size
    val durationMs: Long get() = (nSamples - 1L).coerceAtLeast(0L) * EcgSessionRecorder.EXPECTED_PERIOD_MS

    fun requireCompleteCapture() {
        val reconstructedDuration = durationMs
        val effectiveRate = if (reconstructedDuration > 0L && nSamples > 1) {
            (nSamples - 1) * 1000.0 / reconstructedDuration
        } else {
            0.0
        }
        val rateError = abs(effectiveRate - EcgWearContract.DEFAULT_SR_HZ) /
            EcgWearContract.DEFAULT_SR_HZ
        if (nSamples != EcgSessionRecorder.EXPECTED_SAMPLES ||
            rateError > EcgSessionRecorder.RATE_TOLERANCE_FRACTION
        ) {
            throw EcgCaptureException(
                "ECG capture is incomplete: $nSamples samples over $reconstructedDuration ms",
            )
        }
        if (acquisitionFlags != 0) throw EcgCaptureException("ECG capture contains acquisition errors")
    }

    internal fun encode(watchInfo: String): RecordedSession {
        requireCompleteCapture()
        val gzip = EcgCsvWriter.gzipBytes(
            EcgCsvWriter.encodeCaptureV3(
                wallStartMs = wallStartMs,
                sensorStartMs = sensorTimestampsMs.first(),
                valuesMv = values,
                sampleFlags = flags,
                sensorTimestampsMsRaw = sensorTimestampsMs,
                batchSequence = batchSequence,
                batchSampleOffset = batchSampleOffset,
                batchSize = batchSize,
                wrist = wrist,
                signFactor = signFactor,
                watchInfo = watchInfo,
                captureSource = CaptureSource.HARDWARE,
                bpmObservations = bpmObservations,
                liveBpmAlgorithmId = LiveBpmSummarizer.ALGORITHM_ID,
                listenerDurationMs = listenerDurationMs,
                gapCount = gapCount,
                missingSampleCount = missingSampleCount,
                sequenceGapCount = sequenceGapCount,
                contactLossCount = contactLossCount,
                clippedSampleCount = clippedSampleCount,
                acquisitionFlags = acquisitionFlags,
                minThresholdMv = minThresholdMv,
                maxThresholdMv = maxThresholdMv,
                repeatedTimestampCount = repeatedTimestampCount,
                batchCount = batchCount,
                rawTimingTrust = TimingTrust.UNVERIFIED,
            ),
        )
        return RecordedSession(sessionId, gzip, values.size)
    }
}

data class RecordedSession(
    val sessionId: String,
    val gzip: ByteArray,
    val nSamples: Int,
)

typealias LiveBpmObservation = app.galaxyvitals.domain.LiveBpmObservation

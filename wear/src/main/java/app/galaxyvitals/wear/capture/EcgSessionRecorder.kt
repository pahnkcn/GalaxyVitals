package app.galaxyvitals.wear.capture

import app.galaxyvitals.data.protocol.EcgCsvWriter
import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.domain.CaptureSource
import app.galaxyvitals.domain.EcgSampleFlags
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
    private var size = 0
    private var sensorStartMs = -1L
    private var previousTimestampMs = -1L
    private var previousSequence = -1
    private var gapCount = 0
    private var missingSampleCount = 0
    private var sequenceGapCount = 0
    private var contactLossCount = 0
    private var clippedSampleCount = 0
    private var acquisitionFlags = 0
    private var minThresholdMv: Float? = null
    private var maxThresholdMv: Float? = null

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
            previousSequence = -1
            gapCount = 0
            missingSampleCount = 0
            sequenceGapCount = 0
            contactLossCount = 0
            clippedSampleCount = 0
            acquisitionFlags = 0
            minThresholdMv = null
            maxThresholdMv = null
            recording = true
        }
    }

    fun addEcgAtomically(batch: EcgBatch) = addEcg(batch)

    @Suppress("UNUSED_PARAMETER")
    fun addBpmObservation(status: String) = Unit

    fun addEcg(batch: EcgBatch) {
        synchronized(lock) {
            if (!recording || batch.samplesMv.isEmpty()) return
            if (!batch.contactValid) {
                contactLossCount += batch.samplesMv.size
                acquisitionFlags = acquisitionFlags or EcgSampleFlags.CONTACT_LOSS
                throw EcgCaptureException("ECG contact was lost")
            }
            if (batch.samplesMv.any { !it.isFinite() }) {
                acquisitionFlags = acquisitionFlags or EcgSampleFlags.NONFINITE
                throw EcgCaptureException("ECG contains a non-finite sample")
            }
            if (size + batch.samplesMv.size > MAX_SAMPLES) {
                throw EcgCaptureException("ECG sample buffer overflow")
            }
            if (previousSequence >= 0) {
                val expected = (previousSequence + 1) and 0xff
                if (batch.sequence != expected) {
                    sequenceGapCount++
                    acquisitionFlags = acquisitionFlags or EcgSampleFlags.SEQUENCE_GAP
                    throw EcgCaptureException("ECG sequence gap or duplicate")
                }
            }
            previousSequence = batch.sequence
            minThresholdMv = batch.minThresholdMv ?: minThresholdMv
            maxThresholdMv = batch.maxThresholdMv ?: maxThresholdMv

            batch.samplesMv.indices.forEach { index ->
                if (size >= EXPECTED_SAMPLES) {
                    return@forEach
                }
                val timestamp = batch.sensorTimestampsMs[index]
                if (timestamp < 0L || timestamp < previousTimestampMs) {
                    throw EcgCaptureException("ECG sensor timestamp reversed")
                }
                if (sensorStartMs < 0L) sensorStartMs = timestamp
                val sampleFlags = batch.sampleFlags[index]
                val sample = batch.samplesMv[index]
                val clipped = sampleFlags and EcgSampleFlags.CLIPPED != 0 ||
                    batch.minThresholdMv?.let { sample < it } == true ||
                    batch.maxThresholdMv?.let { sample > it } == true
                if (clipped) {
                    clippedSampleCount++
                    acquisitionFlags = acquisitionFlags or EcgSampleFlags.CLIPPED
                    throw EcgCaptureException("ECG reached the sensor saturation threshold")
                }
                values[size] = sample
                sensorTimestampsMs[size] = timestamp
                flags[size] = sampleFlags
                size++
                previousTimestampMs = timestamp
            }
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
        }
    }

    fun takeSnapshot(): EcgSessionSnapshot = synchronized(lock) {
        check(recording) { "No ECG recording is active" }
        check(size > 0) { "No ECG samples were recorded" }
        recording = false
        EcgSessionSnapshot(
            sessionId = sessionId,
            wallStartMs = wallStartMs,
            sensorStartMs = sensorStartMs,
            values = values.copyOf(size),
            sensorTimestampsMs = sensorTimestampsMs.copyOf(size),
            flags = flags.copyOf(size),
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
) {
    val nSamples: Int get() = values.size
    val durationMs: Long get() = sensorTimestampsMs.last() - sensorTimestampsMs.first()

    fun requireCompleteCapture() {
        val effectiveRate = if (durationMs > 0L) {
            (nSamples - 1) * 1000.0 / durationMs
        } else {
            0.0
        }
        val rateError = abs(effectiveRate - EcgWearContract.DEFAULT_SR_HZ) /
            EcgWearContract.DEFAULT_SR_HZ
        if (nSamples != EcgSessionRecorder.EXPECTED_SAMPLES ||
            rateError > EcgSessionRecorder.RATE_TOLERANCE_FRACTION
        ) {
            throw EcgCaptureException("ECG capture is incomplete: $nSamples samples over $durationMs ms")
        }
        if (acquisitionFlags != 0) throw EcgCaptureException("ECG capture contains acquisition errors")
    }

    internal fun encode(watchInfo: String): RecordedSession {
        // Samsung DataPoints share one timestamp per delivery batch, so the raw
        // sensor clock is quantized. Sequence continuity guarantees a fixed
        // 500 Hz stream, so the stored sample clock is index-based and uniform.
        val relMs = LongArray(sensorTimestampsMs.size) { index ->
            index.toLong() * EcgSessionRecorder.EXPECTED_PERIOD_MS
        }
        val gzip = EcgCsvWriter.gzipBytes(
            EcgCsvWriter.encodeCaptureV2(
                wallStartMs = wallStartMs,
                sensorStartMs = sensorTimestampsMs.first(),
                valuesMv = values,
                relMs = relMs,
                sampleFlags = flags,
                wrist = wrist,
                signFactor = signFactor,
                watchInfo = watchInfo,
                captureSource = CaptureSource.HARDWARE,
                gapCount = gapCount,
                missingSampleCount = missingSampleCount,
                sequenceGapCount = sequenceGapCount,
                contactLossCount = contactLossCount,
                clippedSampleCount = clippedSampleCount,
                acquisitionFlags = acquisitionFlags,
                minThresholdMv = minThresholdMv,
                maxThresholdMv = maxThresholdMv,
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

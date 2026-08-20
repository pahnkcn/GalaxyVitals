package app.healthtrack.wear.capture

import app.healthtrack.data.protocol.EcgCsvWriter
import app.healthtrack.data.protocol.HrStamp
import app.healthtrack.domain.Wrist

class EcgSessionRecorder {
    private val lock = Any()
    private var recording = false
    var sessionId: String = ""
        private set
    private var startMs = 0L
    private var wrist = Wrist.LEFT
    private var signFactor = 1
    private val values = ArrayList<Float>(MAX_SAMPLES)
    private val hr = ArrayList<HrStamp>(MAX_HR_STAMPS)

    val isRecording: Boolean
        get() = synchronized(lock) { recording }

    val sampleCount: Int
        get() = synchronized(lock) { values.size }

    fun begin(sessionId: String, wrist: Wrist, signFactor: Int, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            this.sessionId = sessionId
            this.wrist = wrist
            this.signFactor = signFactor
            startMs = nowMs
            values.clear()
            hr.clear()
            recording = true
        }
    }

    fun addEcg(batch: FloatArray, applySign: Boolean = true) {
        synchronized(lock) {
            if (!recording) return
            if (batch.any { !it.isFinite() }) return
            val count = minOf(batch.size, MAX_SAMPLES - values.size)
            repeat(count) { index ->
                val value = batch[index]
                values.add(if (applySign && signFactor != 1) value * signFactor else value)
            }
        }
    }

    fun addHr(epochMs: Long, bpm: Int) {
        synchronized(lock) {
            if (!recording || hr.size >= MAX_HR_STAMPS) return
            hr.add(HrStamp(epochMs, bpm))
        }
    }

    fun cancel() {
        synchronized(lock) {
            recording = false
            values.clear()
            hr.clear()
        }
    }

    fun takeSnapshot(): EcgSessionSnapshot = synchronized(lock) {
        check(recording) { "No ECG recording is active" }
        recording = false
        EcgSessionSnapshot(
            sessionId = sessionId,
            startMs = startMs,
            values = values.toFloatArray(),
            hr = hr.toList(),
            wrist = wrist,
            signFactor = signFactor,
        ).also {
            values.clear()
            hr.clear()
        }
    }

    fun finish(watchInfo: String): RecordedSession = finish(takeSnapshot(), watchInfo)

    fun finish(snapshot: EcgSessionSnapshot, watchInfo: String): RecordedSession =
        snapshot.encode(watchInfo)

    companion object {
        const val MAX_SAMPLES = 500 * 32
        const val MAX_HR_STAMPS = 256
    }
}

class EcgSessionSnapshot internal constructor(
    val sessionId: String,
    private val startMs: Long,
    private val values: FloatArray,
    private val hr: List<HrStamp>,
    private val wrist: Wrist,
    private val signFactor: Int,
) {
    val nSamples: Int
        get() = values.size

    internal fun encode(watchInfo: String): RecordedSession {
        val gzip = EcgCsvWriter.encodeCaptureGzip(
            sessionStartMs = startMs,
            valuesMv = values,
            hrStamps = hr,
            wrist = wrist,
            signFactor = signFactor,
            watchInfo = watchInfo,
        )
        return RecordedSession(sessionId, gzip, values.size)
    }
}

data class RecordedSession(
    val sessionId: String,
    val gzip: ByteArray,
    val nSamples: Int,
)

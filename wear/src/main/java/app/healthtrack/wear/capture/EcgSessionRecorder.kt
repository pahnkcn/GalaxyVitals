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
    private val values = ArrayList<Float>(EcgCsvWriterDefaults.capacity)
    private val hr = ArrayList<HrStamp>(128)

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
            if (applySign && signFactor != 1) {
                batch.forEach { values.add(it * signFactor) }
            } else {
                batch.forEach { values.add(it) }
            }
        }
    }

    fun addHr(epochMs: Long, bpm: Int) {
        synchronized(lock) {
            if (!recording) return
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

    fun finish(watchInfo: String): RecordedSession {
        synchronized(lock) {
            recording = false
            val gzip = EcgCsvWriter.encodeCaptureGzip(
                sessionStartMs = startMs,
                valuesMv = values.toFloatArray(),
                hrStamps = hr.toList(),
                wrist = wrist,
                signFactor = signFactor,
                watchInfo = watchInfo,
            )
            return RecordedSession(sessionId, gzip, values.size)
        }
    }
}

data class RecordedSession(
    val sessionId: String,
    val gzip: ByteArray,
    val nSamples: Int,
)

private object EcgCsvWriterDefaults {
    const val capacity = 500 * 32
}

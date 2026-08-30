package app.galaxyvitals.wear.debug

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.galaxyvitals.wear.sensors.EcgBatch
import app.galaxyvitals.wear.sensors.EcgSensor
import app.galaxyvitals.wear.sensors.EcgSensorError
import app.galaxyvitals.wear.sensors.EcgSensorErrorCode
import app.galaxyvitals.wear.sensors.EcgSubscription
import app.galaxyvitals.wear.sensors.HeartRateBatch
import app.galaxyvitals.wear.sensors.HeartRateSample
import app.galaxyvitals.wear.sensors.SensorAvailability
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class DebugReplayEcgSensor(
    private val fixtureName: String,
    private val main: Handler = Handler(Looper.getMainLooper()),
) : EcgSensor {
    private val lock = Any()
    private var connected = false
    private var epoch = 0L
    private var activeEpoch = 0L
    private var heartRateEpoch = 0L
    private var activeHeartRateEpoch = 0L
    private var scheduled: ScheduledFuture<*>? = null
    private val replay = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "debug-ecg-replay").apply { isDaemon = true }
    }

    override fun connect(onResult: (SensorAvailability) -> Unit) {
        synchronized(lock) {
            stopStreamingLocked()
            stopHeartRateLocked()
            connected = true
        }
        Log.i(TAG, "replay connect fixture=$fixtureName")
        main.post {
            onResult(SensorAvailability(ready = true, reason = "debug replay $fixtureName"))
        }
    }

    override fun resolvePending(activity: Activity): Boolean = false

    override fun startHeartRate(
        onError: (EcgSensorError) -> Unit,
        onBatch: (HeartRateBatch) -> Unit,
    ): EcgSubscription {
        val startEpoch = synchronized(lock) {
            check(activeEpoch == 0L) { "Debug ECG must stop before heart-rate preflight starts." }
            if (!connected) {
                main.post {
                    onError(
                        EcgSensorError(
                            EcgSensorErrorCode.NOT_CONNECTED,
                            "Debug replay sensor is not connected.",
                        ),
                    )
                }
                return EcgSubscription { }
            }
            check(activeHeartRateEpoch == 0L) { "Debug heart-rate listener is already active." }
            heartRateEpoch += 1
            activeHeartRateEpoch = heartRateEpoch
            heartRateEpoch
        }
        val bpm = DebugReplayFixtures.Fixture.fromId(fixtureName)?.bpm?.toInt() ?: 72
        val samples = listOf(
            HeartRateSample(1_000L, bpm, 1, listOf(60_000 / bpm), listOf(0)),
            HeartRateSample(2_000L, bpm, 1, listOf(60_000 / bpm), listOf(0)),
            HeartRateSample(3_000L, bpm, 1, listOf(60_000 / bpm), listOf(0)),
        )
        main.post {
            if (isCurrentHeartRate(startEpoch)) onBatch(HeartRateBatch(samples))
        }
        return EcgSubscription { closeHeartRateEpoch(startEpoch) }
    }

    override fun startEcg(
        maxDurationMs: Long,
        onError: (EcgSensorError) -> Unit,
        onBatch: (EcgBatch) -> Unit,
        onDeadline: () -> Unit,
    ): EcgSubscription {
        if (maxDurationMs > 30_000L) {
            throw IllegalArgumentException(
                "ECG_ON_DEMAND maxDurationMs must be <= 30000, was $maxDurationMs",
            )
        }
        val startEpoch = synchronized(lock) {
            check(activeHeartRateEpoch == 0L) {
                "Debug heart-rate preflight must stop before ECG starts."
            }
            if (!connected) {
                main.post {
                    onError(
                        EcgSensorError(
                            EcgSensorErrorCode.NOT_CONNECTED,
                            "Debug replay sensor is not connected.",
                        ),
                    )
                }
                return EcgSubscription { }
            }
            stopStreamingLocked()
            epoch += 1
            activeEpoch = epoch
            epoch
        }
        val batches = DebugReplayFixtures.batches(
            fixtureName,
            sampleCount = DebugReplayFixtures.SAMPLE_RATE_HZ * 60,
        )
        Log.i(TAG, "replay start fixture=$fixtureName batches=${batches.size}")
        emitThenSchedule(startEpoch, batches, 0, onBatch)
        return EcgSubscription { closeEpoch(startEpoch) }
    }

    override fun stop() {
        synchronized(lock) {
            stopStreamingLocked()
            stopHeartRateLocked()
        }
    }

    override fun disconnect() {
        synchronized(lock) {
            stopStreamingLocked()
            stopHeartRateLocked()
            connected = false
        }
    }

    private fun emitThenSchedule(
        epoch: Long,
        batches: List<EcgBatch>,
        index: Int,
        onBatch: (EcgBatch) -> Unit,
    ) {
        if (!isCurrent(epoch) || index !in batches.indices) return
        val batch = batches[index]
        logBatch(batch)
        main.post {
            if (isCurrent(epoch)) onBatch(batch)
        }
        val next = index + 1
        if (next >= batches.size) return
        val delayMs = batch.samplesMv.size * DebugReplayFixtures.SAMPLE_PERIOD_MS
        val future = replay.schedule(
            { emitThenSchedule(epoch, batches, next, onBatch) },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
        synchronized(lock) {
            if (activeEpoch != epoch) {
                future.cancel(false)
            } else {
                scheduled = future
            }
        }
    }

    private fun closeEpoch(epoch: Long) {
        synchronized(lock) {
            if (activeEpoch == epoch) stopStreamingLocked()
        }
    }

    private fun closeHeartRateEpoch(epoch: Long) {
        synchronized(lock) {
            if (activeHeartRateEpoch == epoch) stopHeartRateLocked()
        }
    }

    private fun stopStreamingLocked() {
        scheduled?.cancel(false)
        scheduled = null
        if (activeEpoch != 0L) {
            epoch += 1
            activeEpoch = 0L
        }
    }

    private fun stopHeartRateLocked() {
        if (activeHeartRateEpoch != 0L) {
            heartRateEpoch += 1
            activeHeartRateEpoch = 0L
        }
    }

    private fun isCurrent(epoch: Long): Boolean = synchronized(lock) { activeEpoch == epoch }

    private fun isCurrentHeartRate(epoch: Long): Boolean =
        synchronized(lock) { activeHeartRateEpoch == epoch }

    private fun logBatch(batch: EcgBatch) {
        val offsets = batch.ppgGreen?.ecgSampleOffsets
            ?.joinToString(prefix = "[", postfix = "]")
            ?: "[]"
        Log.i(
            TAG,
            "replay fixture=$fixtureName batchSize=${batch.samplesMv.size} " +
                "offsets=$offsets leadOff=${batch.leadOff}",
        )
    }

    companion object {
        private const val TAG = "EcgAcquisition"
    }
}

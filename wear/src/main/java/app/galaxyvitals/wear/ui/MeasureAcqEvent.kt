package app.galaxyvitals.wear.ui

import app.galaxyvitals.wear.sensors.EcgBatch
import app.galaxyvitals.wear.sensors.EcgSensorError
import app.galaxyvitals.wear.sensors.HeartRateBatch
import app.galaxyvitals.wear.sensors.SensorAvailability
import java.util.concurrent.CountDownLatch

/**
 * Everything the acquisition reducer can be told about.
 *
 * A capture is driven by callbacks from three sources that do not share a
 * thread - the Samsung tracker, the timers, and the persistence scope - so
 * every one of them posts an event instead of touching state, and the reducer
 * applies them one at a time on a single-threaded dispatcher. `attemptId` and
 * `generation` are how an event that was already in flight when its listener
 * was torn down gets dropped rather than acted on.
 */
internal sealed interface AcqEvent {
    data object Start : AcqEvent
    data object Cancel : AcqEvent
    data object HostStop : AcqEvent
    data object HostResume : AcqEvent
    data class ConnectResult(val attemptId: Long, val availability: SensorAvailability) : AcqEvent
    data class ConnectTimeout(val attemptId: Long) : AcqEvent
    data class Batch(val attemptId: Long, val generation: Long, val batch: EcgBatch) : AcqEvent
    data class Deadline(val attemptId: Long, val generation: Long) : AcqEvent
    data class SensorError(val attemptId: Long, val generation: Long, val error: EcgSensorError) : AcqEvent
    data class HeartRateBatchReceived(
        val attemptId: Long,
        val generation: Long,
        val batch: HeartRateBatch,
    ) : AcqEvent
    data class HeartRateError(
        val attemptId: Long,
        val generation: Long,
        val error: EcgSensorError,
    ) : AcqEvent
    data class HeartRateTimeout(val attemptId: Long, val generation: Long) : AcqEvent
    data class OffBody(val attemptId: Long, val blocked: Boolean) : AcqEvent
    data class StreamStall(val attemptId: Long, val generation: Long) : AcqEvent
    data class BpmResult(
        val attemptId: Long,
        val generation: Long,
        val snapshot: BpmSnapshot,
        val assessment: BpmAssessment,
    ) : AcqEvent
    data class BpmTick(val attemptId: Long, val generation: Long) : AcqEvent
    data class CountdownTick(val attemptId: Long) : AcqEvent
    data class ContactTimeout(val attemptId: Long, val generation: Long) : AcqEvent
    data class DeadlineSettle(val attemptId: Long, val generation: Long) : AcqEvent
    data class PersistResult(
        val attemptId: Long,
        val success: Boolean,
        val sessionId: String?,
        val pushed: Boolean,
        val error: String?,
    ) : AcqEvent
    data class Shutdown(val done: CountDownLatch) : AcqEvent
}

/** The live-BPM inputs sampled at admission time, so the worker sees a fixed window. */
internal data class BpmSnapshot(
    val analysisWindow: FloatArray,
    val livePpg: List<LivePpgPoint>,
    val signFactor: Int,
    val effectiveSrHz: Double,
    val samsungIbiMs: List<Int>,
    val analysisSampleCount: Int,
    val atSampleIndex: Long,
    val captureElapsedMs: Long,
    val now: Long,
    val epoch: BpmEpoch,
)

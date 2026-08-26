package app.galaxyvitals.wear.sensors

import android.app.Activity
import android.os.Handler
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.data.DataPoint
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface SamsungDeadlineScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): AutoCloseable
}

internal class HandlerDeadlineScheduler(
    private val handler: Handler,
) : SamsungDeadlineScheduler {
    override fun schedule(delayMs: Long, action: () -> Unit): AutoCloseable {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMs)
        return AutoCloseable { handler.removeCallbacks(runnable) }
    }
}

internal class SamsungEcgResolution {
    @Volatile
    private var pending: HealthTrackerException? = null

    fun remember(exception: HealthTrackerException): SensorIssue {
        pending = if (exception.hasResolution()) exception else null
        return SamsungEcgMapping.connectionIssue(exception)
    }

    fun resolvePending(activity: Activity): Boolean {
        val exception = pending ?: return false
        if (!exception.hasResolution()) return false
        exception.resolve(activity)
        return true
    }

    fun clear() {
        pending = null
    }
}

internal class SamsungEcgOnDemandSession(
    private val tracker: HealthTracker,
    private val scheduler: SamsungDeadlineScheduler,
    private val isCurrent: () -> Boolean = { true },
    private val postMain: (() -> Unit) -> Unit = { it() },
    private val execute: (() -> Unit) -> Unit = { it() },
) {
    fun startEcg(
        maxDurationMs: Long,
        onError: (EcgSensorError) -> Unit,
        onBatch: (EcgBatch) -> Unit,
        onDeadline: () -> Unit,
    ): EcgSubscription {
        SamsungEcgMapping.requireOnDemandDuration(maxDurationMs)
        val listener = object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(data: List<DataPoint>) {
                if (data.isEmpty() || !isCurrent()) return
                val batch = try {
                    SamsungEcgMapping.mapBatch(data)
                } catch (error: Exception) {
                    deliverError(
                        EcgSensorError(
                            EcgSensorErrorCode.INVALID_BATCH,
                            error.message ?: "Samsung returned an invalid ECG batch.",
                        ),
                        onError,
                    )
                    return
                }
                execute {
                    if (!isCurrent()) return@execute
                    postMain {
                        if (isCurrent()) onBatch(batch)
                    }
                }
            }

            override fun onFlushCompleted() = Unit

            override fun onError(error: HealthTracker.TrackerError) {
                deliverError(SamsungEcgMapping.trackerError(error), onError)
            }
        }
        tracker.setEventListener(listener)
        val closed = AtomicBoolean(false)
        val deadline = scheduler.schedule(maxDurationMs) {
            if (!closed.compareAndSet(false, true)) return@schedule
            runCatching { tracker.unsetEventListener() }
            onDeadline()
        }
        return EcgSubscription {
            if (!closed.compareAndSet(false, true)) return@EcgSubscription
            deadline.close()
            runCatching { tracker.unsetEventListener() }
        }
    }

    private fun deliverError(error: EcgSensorError, onError: (EcgSensorError) -> Unit) {
        execute {
            if (!isCurrent()) return@execute
            postMain {
                if (isCurrent()) onError(error)
            }
        }
    }
}

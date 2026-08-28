package app.galaxyvitals.wear.sensors

import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import java.util.concurrent.atomic.AtomicBoolean

internal class SamsungHeartRateSession(
    private val tracker: HealthTracker,
    private val isCurrent: () -> Boolean = { true },
    private val postMain: (() -> Unit) -> Unit = { it() },
    private val execute: (() -> Unit) -> Unit = { it() },
) {
    fun start(
        onError: (EcgSensorError) -> Unit,
        onBatch: (HeartRateBatch) -> Unit,
    ): EcgSubscription {
        val closed = AtomicBoolean(false)
        val listener = object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(data: List<DataPoint>) {
                if (data.isEmpty() || closed.get() || !isCurrent()) return
                val points = data.toList()
                execute {
                    if (closed.get() || !isCurrent()) return@execute
                    val batch = try {
                        SamsungHeartRateMapping.mapBatch(points)
                    } catch (error: Exception) {
                        postMain {
                            if (!closed.get() && isCurrent()) {
                                onError(
                                    EcgSensorError(
                                        EcgSensorErrorCode.INVALID_BATCH,
                                        error.message ?: "Samsung returned an invalid heart-rate batch.",
                                    ),
                                )
                            }
                        }
                        return@execute
                    }
                    postMain {
                        if (!closed.get() && isCurrent()) onBatch(batch)
                    }
                }
            }

            override fun onFlushCompleted() = Unit

            override fun onError(error: HealthTracker.TrackerError) {
                deliver(closed, onError, SamsungEcgMapping.trackerError(error))
            }
        }
        tracker.setEventListener(listener)
        return EcgSubscription {
            if (!closed.compareAndSet(false, true)) return@EcgSubscription
            runCatching { tracker.unsetEventListener() }
        }
    }

    private fun deliver(
        closed: AtomicBoolean,
        onError: (EcgSensorError) -> Unit,
        error: EcgSensorError,
    ) {
        execute {
            if (closed.get() || !isCurrent()) return@execute
            postMain {
                if (!closed.get() && isCurrent()) onError(error)
            }
        }
    }
}

package app.galaxyvitals.wear.capture

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

class MeasureForegroundLeaseManager internal constructor(
    private val startAction: () -> Unit,
    private val stopAction: () -> Unit,
) {
    constructor(context: Context) : this(
        startAction = { MeasureForegroundService.start(context.applicationContext) },
        stopAction = { MeasureForegroundService.stop(context.applicationContext) },
    )

    private val lock = Any()
    private var leaseCount = 0

    fun acquire(): Lease = synchronized(lock) {
        if (leaseCount == 0) startAction()
        leaseCount += 1
        Lease(this)
    }

    internal val activeLeaseCount: Int
        get() = synchronized(lock) { leaseCount }

    private fun release() {
        synchronized(lock) {
            check(leaseCount > 0) { "Foreground lease count underflow" }
            leaseCount -= 1
            if (leaseCount == 0) {
                try {
                    stopAction()
                } catch (_: Exception) {
                    // The system may already have stopped the service.
                }
            }
        }
    }

    class Lease internal constructor(
        private val owner: MeasureForegroundLeaseManager,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) owner.release()
        }
    }
}

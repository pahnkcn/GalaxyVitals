package app.galaxyvitals.wear.ui

/**
 * How often a live BPM estimate is allowed to be computed.
 *
 * Beat detection over a 10 s window is far too expensive to run per batch - the
 * sensor delivers one every ~20 ms - and the screen cannot use more than about
 * one update a second anyway. So a batch only marks the window dirty, and this
 * decides when that dirt is worth paying for.
 *
 * The in-flight latch matters as much as the interval: the estimate runs on a
 * compute dispatcher and reports back through the event channel, so without it
 * a slow estimate would let a second one start on the same window and publish
 * out of order.
 */
internal class LiveBpmScheduler(private val intervalMs: Long) {

    /**
     * Estimates actually computed this attempt.
     *
     * Incremented on the compute dispatcher and read from elsewhere, so it is
     * volatile: the count is a cadence observation, not part of the reducer's
     * single-threaded state.
     */
    @Volatile
    var computeCount = 0
        private set

    private var dirty = false
    private var inFlight = false
    private var lastScheduledAt = 0L

    fun reset() {
        dirty = false
        inFlight = false
        lastScheduledAt = 0L
        computeCount = 0
    }

    /** Forget the throttle without clearing the compute count, for a new window. */
    fun restartWindow() {
        lastScheduledAt = 0L
        dirty = false
        inFlight = false
    }

    fun markDirty() {
        dirty = true
    }

    /** An estimate has come back, or its worker was cancelled. */
    fun releaseInFlight() {
        inFlight = false
        dirty = false
    }

    /**
     * True when an estimate should be started at [now], claiming the slot.
     *
     * Claiming and asking are one step on purpose: the reducer is
     * single-threaded, but splitting them would still leave a window in which a
     * caller could ask twice and launch twice.
     */
    fun shouldAdmit(now: Long): Boolean {
        if (!dirty || inFlight) return false
        if (lastScheduledAt != 0L && now - lastScheduledAt < intervalMs) return false
        lastScheduledAt = now
        dirty = false
        inFlight = true
        return true
    }

    fun countCompute() {
        computeCount++
    }

    /** Cleared when the estimate's slot is released without a result. */
    fun clearInFlight() {
        inFlight = false
    }
}

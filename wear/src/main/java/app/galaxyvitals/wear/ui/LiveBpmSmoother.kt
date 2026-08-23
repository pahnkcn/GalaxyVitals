package app.galaxyvitals.wear.ui

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Follows live pulse smoothly instead of locking or flashing each estimate.
 * Beat-to-beat jitter is averaged; a large jump must repeat before the
 * displayed value snaps.
 */
internal class LiveBpmSmoother {
    private var smoothed: Double? = null
    private var pendingBpm: Int? = null
    private var pendingCount = 0

    fun reset() {
        smoothed = null
        pendingBpm = null
        pendingCount = 0
    }

    fun publish(current: Int?, estimated: Int?): Int? {
        if (estimated == null) return smoothed?.roundToInt() ?: current
        val previous = smoothed
        if (previous == null) {
            smoothed = estimated.toDouble()
            return estimated
        }
        if (abs(estimated - previous) > LARGE_JUMP_BPM) {
            val pending = pendingBpm
            if (pending != null && abs(estimated - pending) <= CONFIRM_BPM) {
                pendingCount += 1
            } else {
                pendingBpm = estimated
                pendingCount = 1
            }
            if (pendingCount >= CONFIRM_COUNT) {
                pendingBpm = null
                pendingCount = 0
                smoothed = estimated.toDouble()
                return estimated
            }
            return previous.roundToInt()
        }
        pendingBpm = null
        pendingCount = 0
        val next = previous + ALPHA * (estimated - previous)
        smoothed = next
        return next.roundToInt()
    }

    private companion object {
        const val ALPHA = 0.12
        const val LARGE_JUMP_BPM = 12.0
        const val CONFIRM_BPM = 4
        const val CONFIRM_COUNT = 2
    }
}

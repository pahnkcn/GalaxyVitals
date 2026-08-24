package app.galaxyvitals.wear.ui

import kotlin.math.abs

/**
 * Time-based live BPM smoother. Callers should re-evaluate about once per second.
 * Small changes use EWMA; jumps over 12 BPM need a second candidate ≥ 900 ms later.
 */
internal class LiveBpmSmoother {
    private var smoothed: Double? = null
    private var displayed: BpmEstimate? = null
    private var lastAcceptedAt: Long? = null
    private var pendingBpm: Double? = null
    private var pendingAt: Long? = null

    fun reset() {
        smoothed = null
        displayed = null
        lastAcceptedAt = null
        pendingBpm = null
        pendingAt = null
    }

    fun publish(nowMs: Long, estimated: BpmEstimate?): LiveBpmState {
        if (estimated == null) return onMissing(nowMs)
        val previous = smoothed
        if (previous == null) {
            pendingBpm = null
            pendingAt = null
            return accept(nowMs, estimated, estimated.bpm)
        }
        if (abs(estimated.bpm - previous) > LARGE_JUMP_BPM) {
            val pending = pendingBpm
            val pendingTime = pendingAt
            if (pending != null &&
                pendingTime != null &&
                abs(estimated.bpm - pending) <= CONFIRM_BPM &&
                nowMs - pendingTime >= CONFIRM_GAP_MS
            ) {
                pendingBpm = null
                pendingAt = null
                return accept(nowMs, estimated, estimated.bpm)
            }
            if (pending == null || pendingTime == null || abs(estimated.bpm - pending) > CONFIRM_BPM) {
                pendingBpm = estimated.bpm
                pendingAt = nowMs
            }
            return LiveBpmState(LiveBpmAvailability.RELIABLE, displayed)
        }
        pendingBpm = null
        pendingAt = null
        val next = previous + ALPHA * (estimated.bpm - previous)
        return accept(nowMs, estimated, next)
    }

    private fun onMissing(nowMs: Long): LiveBpmState {
        val acceptedAt = lastAcceptedAt
        val current = displayed
        if (smoothed == null || acceptedAt == null || current == null) {
            return LiveBpmState(LiveBpmAvailability.COLLECTING)
        }
        if (nowMs - acceptedAt > STALE_MS) {
            reset()
            return LiveBpmState(
                availability = LiveBpmAvailability.UNRELIABLE,
                estimate = null,
                reason = "stale",
            )
        }
        return LiveBpmState(LiveBpmAvailability.RELIABLE, current)
    }

    private fun accept(nowMs: Long, estimated: BpmEstimate, bpm: Double): LiveBpmState {
        smoothed = bpm
        lastAcceptedAt = nowMs
        displayed = estimated.copy(bpm = bpm, updatedAtElapsedMs = nowMs)
        return LiveBpmState(LiveBpmAvailability.RELIABLE, displayed)
    }

    private companion object {
        const val ALPHA = 0.25
        const val LARGE_JUMP_BPM = 12.0
        const val CONFIRM_BPM = 4.0
        const val CONFIRM_GAP_MS = 900L
        const val STALE_MS = 3_000L
    }
}

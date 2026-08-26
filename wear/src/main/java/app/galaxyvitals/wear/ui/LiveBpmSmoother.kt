package app.galaxyvitals.wear.ui

import kotlin.math.abs

/**
 * Time-based live BPM smoother. Callers should re-evaluate about once per second.
 * Small changes use EWMA; jumps over 12 BPM need a second candidate ≥ 900 ms later.
 * Stale age is evaluated on every publish, including conflicting non-null estimates.
 */
internal class LiveBpmSmoother {
    private var smoothed: Double? = null
    private var displayed: BpmEstimate? = null
    private var lastAcceptedAt: Long? = null
    private var pendingBpm: Double? = null
    private var pendingAt: Long? = null
    private var transitioning = false

    fun reset() {
        smoothed = null
        displayed = null
        lastAcceptedAt = null
        pendingBpm = null
        pendingAt = null
        transitioning = false
    }

    fun seed(nowMs: Long, estimated: BpmEstimate): LiveBpmState {
        pendingBpm = null
        pendingAt = null
        transitioning = false
        return finish(nowMs, accept(nowMs, estimated, estimated.bpm), accepted = true)
    }

    fun publish(nowMs: Long, estimated: BpmEstimate?): LiveBpmState {
        if (estimated == null) return finish(nowMs, onMissing(), accepted = false)
        val previous = smoothed
        if (previous == null) {
            pendingBpm = null
            pendingAt = null
            transitioning = false
            return finish(nowMs, accept(nowMs, estimated, estimated.bpm), accepted = true)
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
                transitioning = false
                return finish(nowMs, accept(nowMs, estimated, estimated.bpm), accepted = true)
            }
            if (pending == null || pendingTime == null || abs(estimated.bpm - pending) > CONFIRM_BPM) {
                pendingBpm = estimated.bpm
                pendingAt = nowMs
            }
            transitioning = true
            return finish(
                nowMs,
                LiveBpmState(LiveBpmAvailability.TRANSITIONING, estimate = null, reason = "LARGE_JUMP"),
                accepted = false,
            )
        }
        pendingBpm = null
        pendingAt = null
        transitioning = false
        val next = previous + ALPHA * (estimated.bpm - previous)
        return finish(nowMs, accept(nowMs, estimated, next), accepted = true)
    }

    private fun onMissing(): LiveBpmState {
        val acceptedAt = lastAcceptedAt
        val current = displayed
        if (smoothed == null || acceptedAt == null || current == null) {
            return LiveBpmState(LiveBpmAvailability.COLLECTING)
        }
        if (transitioning) {
            return LiveBpmState(
                availability = LiveBpmAvailability.TRANSITIONING,
                estimate = null,
                reason = "LARGE_JUMP",
            )
        }
        return LiveBpmState(LiveBpmAvailability.RELIABLE, current)
    }

    private fun accept(nowMs: Long, estimated: BpmEstimate, bpm: Double): LiveBpmState {
        smoothed = bpm
        lastAcceptedAt = nowMs
        displayed = estimated.copy(bpm = bpm, updatedAtElapsedMs = nowMs)
        transitioning = false
        return LiveBpmState(LiveBpmAvailability.RELIABLE, displayed)
    }

    private fun finish(nowMs: Long, state: LiveBpmState, accepted: Boolean): LiveBpmState {
        val acceptedAt = lastAcceptedAt
        val age = if (acceptedAt == null) 0L else nowMs - acceptedAt
        if (!accepted && acceptedAt != null && age > STALE_MS) {
            reset()
            return LiveBpmState(
                availability = LiveBpmAvailability.UNRELIABLE,
                estimate = null,
                reason = "stale",
                estimateAgeMs = age,
            )
        }
        return state.copy(estimateAgeMs = age)
    }

    private companion object {
        const val ALPHA = 0.25
        const val LARGE_JUMP_BPM = 12.0
        const val CONFIRM_BPM = 4.0
        const val CONFIRM_GAP_MS = 900L
        const val STALE_MS = 3_000L
    }
}

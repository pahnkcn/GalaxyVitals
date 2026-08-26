package app.galaxyvitals.wear.ui

import com.google.common.truth.Truth.assertThat
import kotlin.math.roundToInt
import org.junit.Test

class LiveBpmSmootherTest {
    @Test
    fun firstEstimateIsAccepted() {
        val smoother = LiveBpmSmoother()
        val state = smoother.publish(nowMs = 0L, estimated = estimate(72.0, 0L))
        assertThat(state.availability).isEqualTo(LiveBpmAvailability.RELIABLE)
        assertThat(state.estimate?.bpm?.roundToInt()).isEqualTo(72)
    }

    @Test
    fun restJitterTracksSlowlyWithoutJumpingToEdges() {
        val smoother = LiveBpmSmoother()
        smoother.publish(0L, estimate(72.0, 0L))
        val shown = listOf(69, 75, 70, 74, 71, 73, 69, 75).mapIndexed { index, bpm ->
            val now = (index + 1) * 1_000L
            smoother.publish(now, estimate(bpm.toDouble(), now)).estimate!!.bpm.roundToInt()
        }
        assertThat(shown.min()).isAtLeast(71)
        assertThat(shown.max()).isAtMost(73)
    }

    @Test
    fun sustainedChangeIsFollowed() {
        val smoother = LiveBpmSmoother()
        var state = smoother.publish(0L, estimate(72.0, 0L))
        repeat(12) { step ->
            val now = (step + 1) * 1_000L
            state = smoother.publish(now, estimate(80.0, now))
        }
        val shown = state.estimate!!.bpm.roundToInt()
        assertThat(shown).isAtLeast(77)
        assertThat(shown).isAtMost(80)
    }

    @Test
    fun firstLargeJumpIsTransitioningAndHidesNumber() {
        val smoother = LiveBpmSmoother()
        smoother.publish(0L, estimate(72.0, 0L))
        val jumped = smoother.publish(1_000L, estimate(140.0, 1_000L))
        assertThat(jumped.availability).isEqualTo(LiveBpmAvailability.TRANSITIONING)
        assertThat(jumped.estimate).isNull()
        val afterNear = smoother.publish(2_000L, estimate(68.0, 2_000L))
        assertThat(afterNear.availability).isEqualTo(LiveBpmAvailability.RELIABLE)
        assertThat(afterNear.estimate!!.bpm.roundToInt()).isAtLeast(71)
        assertThat(afterNear.estimate!!.bpm.roundToInt()).isAtMost(72)
    }

    @Test
    fun confirmedJumpIsAcceptedWhenCandidatesAre900MsApart() {
        val smoother = LiveBpmSmoother()
        smoother.publish(0L, estimate(72.0, 0L))
        val jumped = smoother.publish(1_000L, estimate(140.0, 1_000L))
        assertThat(jumped.availability).isEqualTo(LiveBpmAvailability.TRANSITIONING)
        assertThat(jumped.estimate).isNull()
        val confirmed = smoother.publish(1_900L, estimate(138.0, 1_900L))
        assertThat(confirmed.availability).isEqualTo(LiveBpmAvailability.RELIABLE)
        assertThat(confirmed.estimate!!.bpm.roundToInt()).isEqualTo(138)
    }

    @Test
    fun jumpIsNotAcceptedBefore900Ms() {
        val smoother = LiveBpmSmoother()
        smoother.publish(0L, estimate(72.0, 0L))
        smoother.publish(1_000L, estimate(140.0, 1_000L))
        val early = smoother.publish(1_899L, estimate(138.0, 1_899L))
        assertThat(early.availability).isEqualTo(LiveBpmAvailability.TRANSITIONING)
        assertThat(early.estimate).isNull()
    }

    @Test
    fun missingDuringTransitionDoesNotRestoreReliableNumber() {
        val smoother = LiveBpmSmoother()
        smoother.publish(0L, estimate(72.0, 0L))
        smoother.publish(1_000L, estimate(140.0, 1_000L))
        val missing = smoother.publish(2_000L, estimated = null)
        assertThat(missing.availability).isEqualTo(LiveBpmAvailability.TRANSITIONING)
        assertThat(missing.estimate).isNull()
    }

    @Test
    fun alternatingJumpsExpireReliableAfter3Seconds() {
        val smoother = LiveBpmSmoother()
        assertThat(smoother.publish(0L, estimate(90.0, 0L)).availability)
            .isEqualTo(LiveBpmAvailability.RELIABLE)
        assertThat(smoother.publish(1_000L, estimate(115.0, 1_000L)).availability)
            .isEqualTo(LiveBpmAvailability.TRANSITIONING)
        assertThat(smoother.publish(2_000L, estimate(70.0, 2_000L)).availability)
            .isNotEqualTo(LiveBpmAvailability.RELIABLE)
        val expired = smoother.publish(3_001L, estimate(130.0, 3_001L))
        assertThat(expired.availability).isEqualTo(LiveBpmAvailability.UNRELIABLE)
        assertThat(expired.estimate).isNull()
        assertThat(expired.reason).isEqualTo("stale")
    }

    @Test
    fun continuousConflictingNonNullEstimatesAreNeverReliableAfter3Seconds() {
        val smoother = LiveBpmSmoother()
        smoother.publish(0L, estimate(90.0, 0L))
        val candidates = listOf(115.0, 70.0, 130.0, 60.0)
        var last = LiveBpmState(LiveBpmAvailability.COLLECTING)
        candidates.forEachIndexed { index, bpm ->
            val now = (index + 1) * 1_000L
            last = smoother.publish(now, estimate(bpm, now))
            if (now > 3_000L) {
                assertThat(last.availability).isNotEqualTo(LiveBpmAvailability.RELIABLE)
            }
        }
        assertThat(last.availability).isEqualTo(LiveBpmAvailability.UNRELIABLE)
        assertThat(last.estimate).isNull()
        assertThat(last.estimateAgeMs).isGreaterThan(3_000L)
    }

    @Test
    fun missingEstimateKeepsCurrentWithinThreeSeconds() {
        val smoother = LiveBpmSmoother()
        smoother.publish(0L, estimate(72.0, 0L))
        val held = smoother.publish(3_000L, estimated = null)
        assertThat(held.availability).isEqualTo(LiveBpmAvailability.RELIABLE)
        assertThat(held.estimate!!.bpm.roundToInt()).isEqualTo(72)
    }

    @Test
    fun staleEstimateAfter3SecondsIsUnreliableAndClearsBpm() {
        val smoother = LiveBpmSmoother()
        smoother.publish(0L, estimate(72.0, 0L))
        val stale = smoother.publish(3_001L, estimated = null)
        assertThat(stale.availability).isEqualTo(LiveBpmAvailability.UNRELIABLE)
        assertThat(stale.estimate).isNull()
    }

    @Test
    fun startsCollectingUntilFirstEstimate() {
        val smoother = LiveBpmSmoother()
        val state = smoother.publish(0L, estimated = null)
        assertThat(state.availability).isEqualTo(LiveBpmAvailability.COLLECTING)
        assertThat(state.estimate).isNull()
    }

    @Test
    fun seedShowsPreflightBpmImmediately() {
        val smoother = LiveBpmSmoother()
        val seeded = smoother.seed(nowMs = 4_000L, estimated = estimate(72.0, 4_000L, BpmEpoch.PREFLIGHT))
        assertThat(seeded.availability).isEqualTo(LiveBpmAvailability.RELIABLE)
        assertThat(seeded.estimate?.bpm?.roundToInt()).isEqualTo(72)
        assertThat(seeded.estimate?.epoch).isEqualTo(BpmEpoch.PREFLIGHT)
    }

    private fun estimate(
        bpm: Double,
        nowMs: Long,
        epoch: BpmEpoch = BpmEpoch.CAPTURE,
    ) = BpmEstimate(
        bpm = bpm,
        source = BpmSource.APP_ECG_RR,
        epoch = epoch,
        bSqi = 1.0,
        rrCount = 8,
        updatedAtElapsedMs = nowMs,
    )
}

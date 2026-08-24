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
    fun singleOutlierIsHeld() {
        val smoother = LiveBpmSmoother()
        smoother.publish(0L, estimate(72.0, 0L))
        assertThat(smoother.publish(1_000L, estimate(140.0, 1_000L)).estimate!!.bpm.roundToInt()).isEqualTo(72)
        val afterNear = smoother.publish(2_000L, estimate(68.0, 2_000L)).estimate!!.bpm.roundToInt()
        assertThat(afterNear).isAtLeast(71)
        assertThat(afterNear).isAtMost(72)
    }

    @Test
    fun confirmedJumpIsAcceptedWhenCandidatesAre900MsApart() {
        val smoother = LiveBpmSmoother()
        smoother.publish(0L, estimate(72.0, 0L))
        assertThat(smoother.publish(1_000L, estimate(140.0, 1_000L)).estimate!!.bpm.roundToInt()).isEqualTo(72)
        assertThat(smoother.publish(1_900L, estimate(138.0, 1_900L)).estimate!!.bpm.roundToInt()).isEqualTo(138)
    }

    @Test
    fun jumpIsNotAcceptedBefore900Ms() {
        val smoother = LiveBpmSmoother()
        smoother.publish(0L, estimate(72.0, 0L))
        smoother.publish(1_000L, estimate(140.0, 1_000L))
        val early = smoother.publish(1_899L, estimate(138.0, 1_899L))
        assertThat(early.estimate!!.bpm.roundToInt()).isEqualTo(72)
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

    private fun estimate(bpm: Double, nowMs: Long) = BpmEstimate(
        bpm = bpm,
        source = BpmSource.ECG,
        bSqi = 1.0,
        rrCount = 8,
        updatedAtElapsedMs = nowMs,
    )
}

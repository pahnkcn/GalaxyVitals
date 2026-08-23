package app.galaxyvitals.wear.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveBpmSmootherTest {
    @Test
    fun firstEstimateIsAccepted() {
        val smoother = LiveBpmSmoother()
        assertThat(smoother.publish(current = null, estimated = 72)).isEqualTo(72)
    }

    @Test
    fun restJitterTracksSlowlyWithoutJumpingToEdges() {
        val smoother = LiveBpmSmoother()
        smoother.publish(null, 72)
        val shown = listOf(69, 75, 70, 74, 71, 73, 69, 75).map { estimate ->
            smoother.publish(72, estimate)!!
        }
        assertThat(shown.min()).isAtLeast(71)
        assertThat(shown.max()).isAtMost(73)
    }

    @Test
    fun sustainedChangeIsFollowed() {
        val smoother = LiveBpmSmoother()
        var shown = smoother.publish(null, 72)!!
        repeat(12) { shown = smoother.publish(shown, 80)!! }
        assertThat(shown).isAtLeast(77)
        assertThat(shown).isAtMost(80)
    }

    @Test
    fun singleOutlierIsHeld() {
        val smoother = LiveBpmSmoother()
        smoother.publish(null, 72)
        assertThat(smoother.publish(72, 140)).isEqualTo(72)
        val afterNear = smoother.publish(72, 68)!!
        assertThat(afterNear).isAtLeast(71)
        assertThat(afterNear).isAtMost(72)
    }

    @Test
    fun confirmedJumpIsAccepted() {
        val smoother = LiveBpmSmoother()
        smoother.publish(null, 72)
        assertThat(smoother.publish(72, 140)).isEqualTo(72)
        assertThat(smoother.publish(72, 138)).isEqualTo(138)
    }

    @Test
    fun missingEstimateKeepsCurrent() {
        val smoother = LiveBpmSmoother()
        smoother.publish(null, 72)
        assertThat(smoother.publish(72, null)).isEqualTo(72)
    }
}

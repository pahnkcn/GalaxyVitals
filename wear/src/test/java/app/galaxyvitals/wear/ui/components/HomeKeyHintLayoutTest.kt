package app.galaxyvitals.wear.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class HomeKeyHintLayoutTest {
    private val layout = HomeKeyHintLayout.forCanvas(width = 400f, height = 400f)

    @Test
    fun homeElectrodeIsTheUpperButton() {
        assertThat(layout.homeCenterY).isLessThan(layout.lowerCenterY)
        assertThat(layout.homeCenterY).isLessThan(200f)
        assertThat(layout.lowerCenterY).isGreaterThan(200f)
    }

    @Test
    fun bothButtonsSitOnTheRightRim() {
        assertThat(layout.homeCenterX).isGreaterThan(400f * 0.75f)
        assertThat(layout.lowerCenterX).isGreaterThan(400f * 0.75f)
        assertThat(layout.homeCenterX).isLessThan(400f)
        assertThat(layout.lowerCenterX).isLessThan(400f)
    }

    @Test
    fun homeKeyStaysOnTheRoundFace() {
        val homeDist = hypot(layout.homeCenterX - 200f, layout.homeCenterY - 200f)
        assertThat(homeDist + layout.buttonWidth / 2f).isLessThan(200f)
    }

    @Test
    fun arrowPointsAtTheHomeElectrodeFromInsideTheFace() {
        assertThat(layout.arrowTipX).isLessThan(layout.homeCenterX)
        assertThat(layout.arrowTailX).isLessThan(layout.arrowTipX)
        assertThat(abs(layout.arrowTipY - layout.homeCenterY)).isLessThan(2f)
        val toHome = hypot(layout.homeCenterX - layout.arrowTipX, layout.homeCenterY - layout.arrowTipY)
        val toLower = hypot(layout.lowerCenterX - layout.arrowTipX, layout.lowerCenterY - layout.arrowTipY)
        assertThat(toHome).isLessThan(toLower)
    }
}

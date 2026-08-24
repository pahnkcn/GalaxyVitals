package app.galaxyvitals.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HealthTrackNavTest {
    @Test
    fun bpAndEcgOwnTheTopBarInset() {
        assertThat(ownsPhoneTopBar(Route.EcgDetail("abc"))).isTrue()
        assertThat(ownsPhoneTopBar(Route.BloodPressure)).isTrue()
        assertThat(ownsPhoneTopBar(Route.Home)).isFalse()
        assertThat(ownsPhoneTopBar(Route.History)).isFalse()
        assertThat(ownsPhoneTopBar(Route.Settings)).isFalse()
    }
}

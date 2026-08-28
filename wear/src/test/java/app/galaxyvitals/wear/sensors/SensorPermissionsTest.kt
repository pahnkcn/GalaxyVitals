package app.galaxyvitals.wear.sensors

import android.Manifest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SensorPermissionsTest {
    @Test
    fun api35UsesLegacyBodySensorsPermission() {
        assertThat(SensorPermissions.requiredForSdk(35))
            .containsExactly(Manifest.permission.BODY_SENSORS)
    }

    @Test
    fun api36UsesRawEcgAndProcessedHeartRatePermissions() {
        assertThat(SensorPermissions.requiredForSdk(36))
            .containsExactly(
                SensorPermissions.READ_ADDITIONAL_HEALTH_DATA,
                SensorPermissions.READ_HEART_RATE,
            )
            .inOrder()
    }
}

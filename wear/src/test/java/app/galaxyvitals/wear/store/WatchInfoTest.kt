package app.galaxyvitals.wear.store

import app.galaxyvitals.wear.BuildConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WatchInfoTest {
    @Test
    fun watchInfoJsonContainsSdk141AndAarSha256() {
        val json = watchInfoJson(
            model = "SM-L350",
            brand = "samsung",
            manufacturer = "samsung",
            os = "Wear OS 6",
            firmware = "fw",
            androidSdk = 35,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE.toString(),
            packageName = "app.galaxyvitals",
        )
        assertThat(json).contains("\"sensorSdk\":\"1.4.1\"")
        assertThat(json).contains("\"sensorAarSha256\":\"893CD5D6564DB0F304BF511A555C1D65CA6BCCC8475FC979FF1D71D50680344C\"")
        assertThat(BuildConfig.SAMSUNG_HEALTH_SENSOR_SDK_VERSION).isEqualTo("1.4.1")
        assertThat(BuildConfig.SAMSUNG_HEALTH_SENSOR_AAR_SHA256)
            .isEqualTo("893CD5D6564DB0F304BF511A555C1D65CA6BCCC8475FC979FF1D71D50680344C")
    }
}

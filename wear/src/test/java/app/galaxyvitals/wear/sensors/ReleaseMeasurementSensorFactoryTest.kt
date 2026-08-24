package app.galaxyvitals.wear.sensors

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ReleaseMeasurementSensorFactoryTest {
    @Test
    fun releaseFactorySourceOnlyConstructsSamsung() {
        val source = releaseFactoryFile().readText()
        assertThat(source).contains("object MeasurementSensorFactory")
        assertThat(source).contains("SamsungEcgSensor")
        assertThat(source).doesNotContain("DebugReplay")
        assertThat(source).doesNotContain("fixture")
        assertThat(source).doesNotContain("SharedPreferences")
    }

    private fun releaseFactoryFile(): File {
        val candidates = listOf(
            File("src/release/java/app/galaxyvitals/wear/sensors/MeasurementSensorFactory.kt"),
            File("wear/src/release/java/app/galaxyvitals/wear/sensors/MeasurementSensorFactory.kt"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Release MeasurementSensorFactory.kt is missing from the release source set.")
    }
}

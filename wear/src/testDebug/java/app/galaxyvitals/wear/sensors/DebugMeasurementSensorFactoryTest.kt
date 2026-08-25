package app.galaxyvitals.wear.sensors

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class DebugMeasurementSensorFactoryTest {
    @Test
    fun debugFactoryLogsHardwareOrReplayBackend() {
        val source = debugFactoryFile().readText()
        assertThat(source).contains("sensor backend=hardware")
        assertThat(source).contains("sensor backend=replay fixture=")
        assertThat(source).contains("DebugReplayEcgSensor")
        assertThat(source).contains("SamsungEcgSensor")
    }

    private fun debugFactoryFile(): File {
        val candidates = listOf(
            File("src/debug/java/app/galaxyvitals/wear/sensors/MeasurementSensorFactory.kt"),
            File("wear/src/debug/java/app/galaxyvitals/wear/sensors/MeasurementSensorFactory.kt"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Debug MeasurementSensorFactory.kt is missing from the debug source set.")
    }
}

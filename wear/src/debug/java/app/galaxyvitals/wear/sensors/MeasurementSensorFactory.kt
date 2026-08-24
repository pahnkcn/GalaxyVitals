package app.galaxyvitals.wear.sensors

import android.app.Application
import app.galaxyvitals.wear.debug.DebugReplayEcgSensor
import app.galaxyvitals.wear.debug.DebugReplayPreferences

object MeasurementSensorFactory {
    fun create(app: Application): EcgSensor {
        val fixture = DebugReplayPreferences.fixtureName(app)
        return if (fixture != null) {
            DebugReplayEcgSensor(fixture)
        } else {
            SamsungEcgSensor(app)
        }
    }
}

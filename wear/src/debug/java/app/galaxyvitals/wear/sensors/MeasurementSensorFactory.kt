package app.galaxyvitals.wear.sensors

import android.app.Application
import android.util.Log
import app.galaxyvitals.wear.debug.DebugReplayEcgSensor
import app.galaxyvitals.wear.debug.DebugReplayPreferences

object MeasurementSensorFactory {
    fun create(app: Application): EcgSensor {
        val fixture = DebugReplayPreferences.fixtureName(app)
        return if (fixture != null) {
            Log.i(TAG, "sensor backend=replay fixture=$fixture")
            DebugReplayEcgSensor(fixture)
        } else {
            Log.i(TAG, "sensor backend=hardware")
            SamsungEcgSensor(app)
        }
    }

    private const val TAG = "EcgAcquisition"
}

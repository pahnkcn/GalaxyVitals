package app.galaxyvitals.wear.sensors

import android.app.Application

object MeasurementSensorFactory {
    fun create(app: Application): EcgSensor = SamsungEcgSensor(app)
}

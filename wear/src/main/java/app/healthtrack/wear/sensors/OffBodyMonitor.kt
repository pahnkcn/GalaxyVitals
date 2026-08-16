package app.healthtrack.wear.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import app.healthtrack.data.protocol.EcgWearContract

class OffBodyMonitor(
    context: Context,
    private val onChange: (blocked: Boolean) -> Unit,
) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
    private var lastOffAt = 0L
    private var off = false

    fun start() {
        val s = sensor
        if (s == null) {
            onChange(false)
            return
        }
        manager?.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    fun isBlocked(): Boolean {
        if (sensor == null) return false
        return off && lastOffAt > 0 &&
            SystemClock.elapsedRealtime() - lastOffAt >= EcgWearContract.OFF_BODY_BLOCK_MS
    }

    override fun onSensorChanged(event: SensorEvent) {
        val nowOff = event.values.firstOrNull()?.let { it < 0.5f } ?: false
        if (nowOff && !off) lastOffAt = SystemClock.elapsedRealtime()
        off = nowOff
        onChange(isBlocked())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

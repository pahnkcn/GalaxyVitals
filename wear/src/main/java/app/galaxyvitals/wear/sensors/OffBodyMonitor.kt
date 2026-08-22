package app.galaxyvitals.wear.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.galaxyvitals.data.protocol.EcgWearContract

interface OffBodyGate {
    fun start()
    fun stop()
    fun isBlocked(): Boolean
}

class OffBodyMonitor(
    context: Context,
    private val onChange: (blocked: Boolean) -> Unit,
) : SensorEventListener, OffBodyGate {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
    private val main = Handler(Looper.getMainLooper())
    private var lastOffAt = 0L
    private var off = false
    private val notifyBlocked = Runnable {
        if (isBlocked()) onChange(true)
    }

    override fun start() {
        resetDetection()
        val s = sensor
        if (s == null) {
            onChange(false)
            return
        }
        manager?.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun stop() {
        manager?.unregisterListener(this)
        resetDetection()
    }

    override fun isBlocked(): Boolean {
        if (sensor == null) return false
        return off && lastOffAt > 0 &&
            SystemClock.elapsedRealtime() - lastOffAt >= EcgWearContract.OFF_BODY_BLOCK_MS
    }

    override fun onSensorChanged(event: SensorEvent) {
        val nowOff = event.values.firstOrNull()?.let { it < 0.5f } ?: false
        if (nowOff == off) return
        off = nowOff
        main.removeCallbacks(notifyBlocked)
        if (nowOff) {
            lastOffAt = SystemClock.elapsedRealtime()
            onChange(false)
            main.postDelayed(notifyBlocked, EcgWearContract.OFF_BODY_BLOCK_MS)
        } else {
            lastOffAt = 0L
            onChange(false)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun resetDetection() {
        main.removeCallbacks(notifyBlocked)
        lastOffAt = 0L
        off = false
    }
}

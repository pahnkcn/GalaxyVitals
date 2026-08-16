package app.healthtrack.wear.sensors

import android.os.Handler
import android.os.Looper
import app.healthtrack.data.protocol.DemoEcg
import app.healthtrack.data.protocol.EcgWearContract

class DemoEcgSensor : EcgSensor {
    override val kind: SensorKind = SensorKind.DEMO

    private val main = Handler(Looper.getMainLooper())
    private var hrTick: Runnable? = null
    private var ecgTick: Runnable? = null
    private var index = 0

    override fun connect(onResult: (SensorAvailability) -> Unit) {
        onResult(SensorAvailability(SensorKind.DEMO, ready = true))
    }

    override fun startHr(onHr: (bpm: Int, status: Int) -> Unit) {
        stopHr()
        val tick = object : Runnable {
            override fun run() {
                onHr(68, EcgWearContract.HR_STATUS_OK)
                main.postDelayed(this, 1000L)
            }
        }
        hrTick = tick
        main.post(tick)
    }

    override fun startEcg(onBatch: (mv: FloatArray, leadOff: Boolean) -> Unit) {
        stopEcg()
        val batchSize = 50
        val tick = object : Runnable {
            override fun run() {
                val batch = FloatArray(batchSize) { DemoEcg.sampleMv(index++) }
                onBatch(batch, false)
                main.postDelayed(this, 100L)
            }
        }
        ecgTick = tick
        main.post(tick)
    }

    override fun stop() {
        stopHr()
        stopEcg()
        index = 0
    }

    override fun disconnect() = stop()

    private fun stopHr() {
        hrTick?.let(main::removeCallbacks)
        hrTick = null
    }

    private fun stopEcg() {
        ecgTick?.let(main::removeCallbacks)
        ecgTick = null
    }
}

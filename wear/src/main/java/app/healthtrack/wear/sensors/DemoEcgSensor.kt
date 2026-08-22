package app.healthtrack.wear.sensors

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.healthtrack.data.protocol.DemoEcg

class DemoEcgSensor : EcgSensor {
    override val kind: SensorKind = SensorKind.DEMO

    private val main = Handler(Looper.getMainLooper())
    private var ecgTick: Runnable? = null
    private var index = 0
    private var sensorStartMs = 0L
    private var sequence = 0

    override fun connect(onResult: (SensorAvailability) -> Unit) {
        onResult(SensorAvailability(SensorKind.DEMO, ready = true))
    }

    override fun startEcg(
        onError: (EcgSensorError) -> Unit,
        onBatch: (EcgBatch) -> Unit,
    ) {
        stopEcg()
        if (sensorStartMs == 0L) sensorStartMs = SystemClock.elapsedRealtime()
        val batchSize = 50
        val tick = object : Runnable {
            override fun run() {
                val firstIndex = index
                val samples = FloatArray(batchSize) { DemoEcg.sampleMv(index++) }
                onBatch(
                    EcgBatch(
                        samplesMv = samples,
                        sensorTimestampsMs = LongArray(batchSize) { offset ->
                            sensorStartMs + (firstIndex + offset) * 2L
                        },
                        sequence = sequence,
                        leadOff = 0,
                        minThresholdMv = -5f,
                        maxThresholdMv = 5f,
                        sampleFlags = IntArray(batchSize),
                    ),
                )
                sequence = (sequence + 1) and 0xff
                main.postDelayed(this, 100L)
            }
        }
        ecgTick = tick
        main.post(tick)
    }

    override fun stop() {
        stopEcg()
        index = 0
        sequence = 0
        sensorStartMs = 0L
    }

    override fun disconnect() = stop()

    override fun stopEcg() {
        ecgTick?.let(main::removeCallbacks)
        ecgTick = null
    }
}

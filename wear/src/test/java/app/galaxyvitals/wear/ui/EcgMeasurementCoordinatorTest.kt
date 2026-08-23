package app.galaxyvitals.wear.ui

import app.galaxyvitals.domain.Wrist
import app.galaxyvitals.wear.capture.EcgSessionRecorder
import app.galaxyvitals.wear.sensors.EcgBatch
import app.galaxyvitals.wear.sensors.EcgSensor
import app.galaxyvitals.wear.sensors.EcgSensorError
import app.galaxyvitals.wear.sensors.EcgSubscription
import app.galaxyvitals.wear.sensors.OffBodyGate
import app.galaxyvitals.wear.sensors.SensorAvailability
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Test

class EcgMeasurementCoordinatorTest {
    @Test
    fun stableContactStartsOneListenerRecorderAndForegroundLease() {
        val harness = Harness()
        harness.coordinator.startHardware()

        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Warmup)
        harness.now = 100L
        harness.sensor.emit(0, batch(sequence = 0, valueMv = 140f))
        harness.now = 400L
        harness.sensor.emit(0, batch(sequence = 1, valueMv = 140f))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Ready)
        harness.now = 2_101L
        harness.sensor.emit(0, batch(sequence = 2, valueMv = 140f))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Ready)
        assertThat(harness.recorder.isRecording).isFalse()
        assertThat(harness.coordinator.state.value.liveMv).isNotEmpty()
        assertThat(harness.coordinator.state.value.liveMv.maxOf { kotlin.math.abs(it) }).isLessThan(5f)

        harness.now = 4_101L
        harness.sensor.emit(0, batch(sequence = 3))

        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)
        assertThat(harness.sensor.startCount).isEqualTo(1)
        assertThat(harness.foregroundAcquires).isEqualTo(1)
        assertThat(harness.recorder.isRecording).isTrue()
        assertThat(harness.recorder.sampleCount).isEqualTo(0)

        harness.sensor.emit(0, batch(sequence = 4))
        assertThat(harness.recorder.sampleCount).isEqualTo(10)
    }

    @Test
    fun missingFingerContactAsksToTouchTheButton() {
        val harness = Harness()
        harness.coordinator.startHardware()
        harness.now = 100L
        harness.sensor.emit(0, batch(sequence = 0, leadOff = 1))

        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.LeadOff)
        assertThat(harness.coordinator.state.value.status).isEqualTo("Touch the button")
    }

    @Test
    fun contactMustRemainStableForFullDebounce() {
        val harness = Harness()
        harness.coordinator.startHardware()
        harness.now = 100L
        harness.sensor.emit(0, batch(sequence = 0))
        harness.now = 250L
        harness.sensor.emit(0, batch(sequence = 1, leadOff = 1))
        harness.now = 300L
        harness.sensor.emit(0, batch(sequence = 2))
        harness.now = 700L
        harness.sensor.emit(0, batch(sequence = 3))

        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Ready)
        assertThat(harness.foregroundAcquires).isEqualTo(0)

        harness.now = 4_301L
        harness.sensor.emit(0, batch(sequence = 4))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)
    }

    @Test
    fun recordingFailureIsTerminalUntilRetryAndStaleCallbacksAreIgnored() {
        val harness = Harness()
        harness.coordinator.startHardware()
        harness.now = 100L
        harness.sensor.emit(0, batch(sequence = 0))
        harness.now = 4_101L
        harness.sensor.emit(0, batch(sequence = 1))
        harness.sensor.emit(0, batch(sequence = 2))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)

        harness.sensor.emit(0, batch(sequence = 3, leadOff = 1))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Failed)
        assertThat(harness.sensor.startCount).isEqualTo(1)
        assertThat(harness.sensor.closeCount).isEqualTo(1)
        assertThat(harness.sensor.stopCount).isEqualTo(1)
        assertThat(harness.sensor.disconnectCount).isEqualTo(1)
        assertThat(harness.foregroundCloses).isEqualTo(1)

        harness.sensor.emit(0, batch(sequence = 4))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Failed)
        assertThat(harness.sensor.startCount).isEqualTo(1)

        harness.coordinator.retry()
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Warmup)
        assertThat(harness.sensor.startCount).isEqualTo(2)
        harness.sensor.emit(0, batch(sequence = 5, leadOff = 1))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Warmup)
    }

    @Test
    fun stabilizingSensorDoesNotPublishBpm() {
        val harness = Harness()
        harness.coordinator.startHardware()
        harness.now = 100L
        harness.sensor.emit(0, batch(sequence = 0))
        harness.now = 2_101L
        harness.sensor.emit(0, batch(sequence = 1, samples = syntheticQrs(seconds = 3, bpm = 72)))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Ready)
        assertThat(harness.coordinator.state.value.status).isEqualTo("Stabilizing sensor…")
        assertThat(harness.coordinator.state.value.hrBpm).isNull()
    }

    @Test
    fun recordingPublishesLiveBpmFromQrsTrain() {
        val harness = Harness()
        harness.coordinator.startHardware()
        harness.now = 100L
        harness.sensor.emit(0, batch(sequence = 0))
        harness.now = 4_101L
        harness.sensor.emit(0, batch(sequence = 1))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)

        val qrs = syntheticQrs(seconds = 3, bpm = 72)
        var sequence = 2
        var offset = 0
        while (offset < qrs.size) {
            val count = minOf(50, qrs.size - offset)
            harness.now += 100L
            harness.sensor.emit(
                0,
                batch(
                    sequence = sequence,
                    samples = qrs.copyOfRange(offset, offset + count),
                    timestampStartMs = 2_000L + offset * 2L,
                ),
            )
            sequence += 1
            offset += count
        }

        val bpm = harness.coordinator.state.value.hrBpm
        assertThat(bpm).isNotNull()
        assertThat(kotlin.math.abs(bpm!! - 72)).isAtMost(8)
    }

    @Test
    fun recordingPublishesLiveBpmFromPpgGreen() {
        val harness = Harness()
        harness.coordinator.startHardware()
        harness.now = 100L
        harness.sensor.emit(0, batch(sequence = 0))
        harness.now = 4_101L
        harness.sensor.emit(0, batch(sequence = 1))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)

        val ppg = syntheticPpg(seconds = 3, bpm = 68)
        var sequence = 2
        var offset = 0
        while (offset < ppg.size) {
            val count = minOf(50, ppg.size - offset)
            harness.now += 100L
            harness.sensor.emit(
                0,
                batch(
                    sequence = sequence,
                    samples = FloatArray(count) { 0.1f },
                    timestampStartMs = 2_000L + offset * 2L,
                    ppgGreen = ppg.copyOfRange(offset, offset + count),
                ),
            )
            sequence += 1
            offset += count
        }

        val bpm = harness.coordinator.state.value.hrBpm
        assertThat(bpm).isNotNull()
        assertThat(kotlin.math.abs(bpm!! - 68)).isAtMost(8)
    }

    private class Harness {
        val sensor = FakeSensor()
        val recorder = EcgSessionRecorder()
        var now = 1L
        var foregroundAcquires = 0
        var foregroundCloses = 0
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = EcgMeasurementCoordinator(
            sensor = sensor,
            recorder = recorder,
            scope = scope,
            persistenceScope = scope,
            wrist = { Wrist.LEFT },
            acquireForeground = {
                foregroundAcquires += 1
                AutoCloseable { foregroundCloses += 1 }
            },
            save = { _, _ -> Unit },
            pushToPhone = { _, _ -> Unit },
            watchInfo = { "watch" },
            offBodyFactory = { FakeOffBody() },
            mainDispatcher = Dispatchers.Unconfined,
            elapsedRealtime = { now },
            wallClock = { 1_700_000_000_000L + now },
            transitionLogger = {},
        )
    }

    private class FakeSensor : EcgSensor {
        data class Listener(
            val onError: (EcgSensorError) -> Unit,
            val onBatch: (EcgBatch) -> Unit,
        )

        val listeners = arrayListOf<Listener>()
        var startCount = 0
        var closeCount = 0
        var stopCount = 0
        var disconnectCount = 0

        override fun connect(onResult: (SensorAvailability) -> Unit) {
            onResult(SensorAvailability(ready = true))
        }

        override fun startEcg(
            onError: (EcgSensorError) -> Unit,
            onBatch: (EcgBatch) -> Unit,
        ): EcgSubscription {
            startCount += 1
            listeners += Listener(onError, onBatch)
            var closed = false
            return EcgSubscription {
                if (!closed) {
                    closed = true
                    closeCount += 1
                }
            }
        }

        fun emit(listenerIndex: Int, batch: EcgBatch) {
            listeners[listenerIndex].onBatch(batch)
        }

        override fun stop() {
            stopCount += 1
        }

        override fun disconnect() {
            disconnectCount += 1
        }
    }

    private class FakeOffBody : OffBodyGate {
        override fun start() = Unit
        override fun stop() = Unit
        override fun isBlocked(): Boolean = false
    }

    companion object {
        private fun batch(
            sequence: Int,
            leadOff: Int = 0,
            valueMv: Float = 0.1f,
            samples: FloatArray? = null,
            timestampStartMs: Long? = null,
            ppgGreen: IntArray? = null,
        ): EcgBatch {
            val values = samples ?: FloatArray(10) { valueMv }
            val start = timestampStartMs ?: (1_000L + sequence * 20L)
            return EcgBatch(
                samplesMv = values,
                sensorTimestampsMs = LongArray(values.size) { start + it * 2L },
                sequence = sequence and 0xff,
                leadOff = leadOff,
                minThresholdMv = -5f,
                maxThresholdMv = 5f,
                sampleFlags = IntArray(values.size),
                ppgGreen = ppgGreen,
            )
        }

        private fun syntheticPpg(seconds: Int, bpm: Int, srHz: Int = 500): IntArray {
            val n = seconds * srHz
            val period = srHz * 60 / bpm
            val out = IntArray(n)
            val peak = period / 5
            val sigma = period * 0.08
            for (index in 0 until n) {
                val t = index % period
                val gauss = kotlin.math.exp(-((t - peak) * (t - peak)) / (2.0 * sigma * sigma))
                out[index] = (12_000 + 4_000 * gauss).toInt()
            }
            return out
        }

        private fun syntheticQrs(seconds: Int, bpm: Int, srHz: Int = 500): FloatArray {
            val n = seconds * srHz
            val period = srHz * 60 / bpm
            val out = FloatArray(n)
            var peak = period / 2
            while (peak < n) {
                for (offset in -5..5) {
                    val index = peak + offset
                    if (index in 0 until n) {
                        out[index] = (1.5 * kotlin.math.exp(-offset * offset / 6.0)).toFloat()
                    }
                }
                peak += period
            }
            return out
        }
    }
}

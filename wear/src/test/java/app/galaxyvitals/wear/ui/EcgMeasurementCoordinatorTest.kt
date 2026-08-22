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
        harness.sensor.emit(0, batch(sequence = 0))
        harness.now = 400L
        harness.sensor.emit(0, batch(sequence = 1))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Ready)
        harness.now = 601L
        harness.sensor.emit(0, batch(sequence = 2))

        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)
        assertThat(harness.sensor.startCount).isEqualTo(1)
        assertThat(harness.foregroundAcquires).isEqualTo(1)
        assertThat(harness.recorder.isRecording).isTrue()
        assertThat(harness.recorder.sampleCount).isEqualTo(0)

        harness.sensor.emit(0, batch(sequence = 3))
        assertThat(harness.recorder.sampleCount).isEqualTo(10)
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

        harness.now = 801L
        harness.sensor.emit(0, batch(sequence = 4))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)
    }

    @Test
    fun recordingFailureIsTerminalUntilRetryAndStaleCallbacksAreIgnored() {
        val harness = Harness()
        harness.coordinator.startHardware()
        harness.now = 100L
        harness.sensor.emit(0, batch(sequence = 0))
        harness.now = 601L
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
        private fun batch(sequence: Int, leadOff: Int = 0): EcgBatch = EcgBatch(
            samplesMv = FloatArray(10) { 0.1f },
            sensorTimestampsMs = LongArray(10) { 1_000L + sequence * 20L + it * 2L },
            sequence = sequence and 0xff,
            leadOff = leadOff,
            minThresholdMv = -5f,
            maxThresholdMv = 5f,
            sampleFlags = IntArray(10),
        )
    }
}

package app.galaxyvitals.wear.ui

import app.galaxyvitals.data.protocol.EcgCsvParser
import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.domain.Wrist
import app.galaxyvitals.wear.capture.EcgSessionRecorder
import app.galaxyvitals.wear.sensors.EcgBatch
import app.galaxyvitals.wear.sensors.EcgSensor
import app.galaxyvitals.wear.sensors.EcgSensorError
import app.galaxyvitals.wear.sensors.EcgSubscription
import app.galaxyvitals.wear.sensors.OffBodyGate
import app.galaxyvitals.wear.sensors.PpgGreenBatch
import app.galaxyvitals.wear.sensors.SensorAvailability
import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
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
        harness.sensor.emit(0, batch(sequence = 1, samples = syntheticQrs(seconds = 3.0, bpm = 72.0)))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Ready)
        assertThat(harness.coordinator.state.value.status).isEqualTo("Stabilizing sensor…")
        assertThat(harness.coordinator.state.value.hrBpm).isNull()
        assertThat(harness.coordinator.state.value.bpm.availability)
            .isEqualTo(LiveBpmAvailability.COLLECTING)
        assertThat(harness.coordinator.state.value.bpm.estimate).isNull()
    }

    @Test
    fun recordingPublishesLiveBpmFromQrsTrain() {
        val harness = Harness()
        startRecording(harness)
        streamQrs(harness, seconds = 10.0, bpm = 72, startSequence = 2)

        val bpm = harness.coordinator.state.value.hrBpm
        assertThat(bpm).isNotNull()
        assertThat(kotlin.math.abs(bpm!! - 72)).isAtMost(8)
        assertThat(harness.coordinator.state.value.bpm.availability)
            .isEqualTo(LiveBpmAvailability.RELIABLE)
        assertThat(harness.coordinator.state.value.bpm.estimate!!.source).isEqualTo(BpmSource.ECG)
    }

    @Test
    fun recordingPublishesLiveBpmFromSparsePpgCorroboration() {
        val harness = Harness()
        startRecording(harness)
        streamQrs(harness, seconds = 10.0, bpm = 72, startSequence = 2, includePpg = true)

        val bpm = harness.coordinator.state.value.hrBpm
        assertThat(bpm).isNotNull()
        assertThat(kotlin.math.abs(bpm!! - 72)).isAtMost(8)
        assertThat(harness.coordinator.state.value.bpm.estimate!!.source)
            .isEqualTo(BpmSource.ECG_PPG_CORROBORATED)
    }

    @Test
    fun recorderKeepsRawSamplesOnRightWrist() {
        val harness = Harness(wrist = Wrist.RIGHT)
        startRecording(harness)
        val raw = FloatArray(10) { index -> if (index == 0) 1.5f else 0.12f }
        harness.sensor.emit(0, batch(sequence = 2, samples = raw))

        val recorded = harness.recorder.finish("watch")
        val parsed = EcgCsvParser.parseBytes(
            recorded.gzip,
            gzip = true,
            sessionIdHint = requireNotNull(harness.coordinator.state.value.sessionId),
        )
        assertThat(parsed.signFactor).isEqualTo(EcgWearContract.signFactorFor(Wrist.RIGHT))
        assertThat(parsed.samples[0].valueMv).isEqualTo(1.5f)
        assertThat(parsed.samples[1].valueMv).isEqualTo(0.12f)
    }

    @Test
    fun displayLiveMvFollowsWristSignFactor() {
        val left = Harness(wrist = Wrist.LEFT)
        val right = Harness(wrist = Wrist.RIGHT)
        val qrs = syntheticQrs(seconds = 3.0, bpm = 72.0)
        startRecording(left)
        startRecording(right)
        streamPrepared(left, qrs, startSequence = 2, includePpg = false)
        streamPrepared(right, qrs, startSequence = 2, includePpg = false)

        val leftMv = left.coordinator.state.value.liveMv
        val rightMv = right.coordinator.state.value.liveMv
        assertThat(leftMv.size).isEqualTo(rightMv.size)
        assertThat(leftMv.size).isGreaterThan(10)
        for (index in leftMv.indices) {
            assertThat(rightMv[index]).isWithin(1e-4f).of(-leftMv[index])
        }
        assertThat(leftMv.maxOrNull()!!).isGreaterThan(0.2f)
        assertThat(rightMv.minOrNull()!!).isLessThan(-0.2f)
    }

    @Test
    fun globalPpgIndexIsContinuousAcrossMixed5And10SampleBatches() {
        val harness = Harness()
        startRecording(harness)
        val startIndex = harness.coordinator.liveEcgProcessor.nextEcgSampleIndex
        harness.now += 100L
        harness.sensor.emit(
            0,
            batch(
                sequence = 2,
                samples = FloatArray(5) { 0.1f },
                ppgGreen = sparsePpg(intArrayOf(0), startMs = 2_000L),
            ),
        )
        harness.now += 100L
        harness.sensor.emit(
            0,
            batch(
                sequence = 3,
                samples = FloatArray(10) { 0.1f },
                ppgGreen = sparsePpg(intArrayOf(0, 5), startMs = 2_010L),
            ),
        )
        harness.now += 100L
        harness.sensor.emit(
            0,
            batch(
                sequence = 4,
                samples = FloatArray(5) { 0.1f },
                ppgGreen = sparsePpg(intArrayOf(0), startMs = 2_030L),
            ),
        )

        assertThat(harness.coordinator.liveEcgProcessor.livePpg.map { it.ecgSampleIndex })
            .containsExactly(startIndex, startIndex + 5L, startIndex + 10L, startIndex + 15L)
            .inOrder()
    }

    @Test
    fun displayWindowCapsAt1500AndAnalysisWindowCapsAt5000() {
        val harness = Harness()
        startRecording(harness)
        streamQrs(harness, seconds = 12.0, bpm = 72, startSequence = 2)

        assertThat(harness.coordinator.state.value.liveMv.size)
            .isEqualTo(LiveEcgProcessor.DISPLAY_WINDOW_SAMPLES)
        assertThat(harness.coordinator.liveEcgProcessor.analysisSamples.size)
            .isEqualTo(LiveEcgProcessor.ANALYSIS_WINDOW_SAMPLES)
        assertThat(harness.coordinator.state.value.hrBpm).isNotNull()
    }

    private class Harness(wrist: Wrist = Wrist.LEFT) {
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
            wrist = { wrist },
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
            bpmLogger = {},
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
        private fun startRecording(harness: Harness) {
            harness.coordinator.startHardware()
            harness.now = 100L
            harness.sensor.emit(0, batch(sequence = 0))
            harness.now = 4_101L
            harness.sensor.emit(0, batch(sequence = 1))
            assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)
        }

        private fun streamQrs(
            harness: Harness,
            seconds: Double,
            bpm: Int,
            startSequence: Int,
            includePpg: Boolean = false,
        ) {
            streamPrepared(
                harness,
                syntheticQrs(seconds = seconds, bpm = bpm.toDouble()),
                startSequence,
                includePpg,
                bpm,
            )
        }

        private fun streamPrepared(
            harness: Harness,
            qrs: FloatArray,
            startSequence: Int,
            includePpg: Boolean,
            bpm: Int = 72,
        ) {
            val ppg = if (includePpg) {
                LiveBpmEstimatorTest.syntheticSparsePpg(qrs.size, bpm)
            } else {
                null
            }
            var sequence = startSequence
            var offset = 0
            while (offset < qrs.size) {
                val count = minOf(50, qrs.size - offset)
                harness.now += 100L
                val samples = qrs.copyOfRange(offset, offset + count)
                val ppgBatch = ppg?.let { values ->
                    val local = ArrayList<Int>()
                    val offsets = ArrayList<Int>()
                    val timestamps = ArrayList<Long>()
                    for (index in 0 until count) {
                        val global = offset + index
                        if (global % 5 != 0) continue
                        val ppgIndex = global / 5
                        if (ppgIndex >= values.size) continue
                        offsets += index
                        local += values[ppgIndex]
                        timestamps += 2_000L + global * 2L
                    }
                    if (local.isEmpty()) {
                        null
                    } else {
                        PpgGreenBatch(local.toIntArray(), offsets.toIntArray(), timestamps.toLongArray())
                    }
                }
                harness.sensor.emit(
                    0,
                    batch(
                        sequence = sequence,
                        samples = samples,
                        timestampStartMs = 2_000L + offset * 2L,
                        ppgGreen = ppgBatch,
                    ),
                )
                sequence += 1
                offset += count
            }
        }

        private fun batch(
            sequence: Int,
            leadOff: Int = 0,
            valueMv: Float = 0.1f,
            samples: FloatArray? = null,
            timestampStartMs: Long? = null,
            ppgGreen: PpgGreenBatch? = null,
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

        private fun sparsePpg(offsets: IntArray, startMs: Long): PpgGreenBatch = PpgGreenBatch(
            values = IntArray(offsets.size) { 12_000 },
            ecgSampleOffsets = offsets,
            sensorTimestampsMs = LongArray(offsets.size) { startMs + offsets[it] * 2L },
        )

        private fun syntheticQrs(
            seconds: Double,
            bpm: Double,
            srHz: Int = 500,
        ): FloatArray {
            val n = (seconds * srHz).roundToInt()
            val out = DoubleArray(n)
            val period = srHz * 60.0 / bpm
            var peak = period * 0.5
            while (peak < n) {
                val r = peak.roundToInt()
                addGaussian(out, r - (0.18 * srHz).roundToInt(), 0.12, 0.035 * srHz)
                addGaussian(out, r - (0.025 * srHz).roundToInt(), -0.15, 0.008 * srHz)
                addGaussian(out, r, 1.20, 0.010 * srHz)
                addGaussian(out, r + (0.025 * srHz).roundToInt(), -0.28, 0.010 * srHz)
                addGaussian(out, r + (0.22 * srHz).roundToInt(), 0.30, 0.045 * srHz)
                peak += period
            }
            for (index in out.indices) {
                val t = index.toDouble() / srHz
                out[index] += 0.04 * sin(2 * PI * 0.25 * t)
            }
            return FloatArray(n) { out[it].toFloat() }
        }

        private fun addGaussian(out: DoubleArray, center: Int, amplitude: Double, sigma: Double) {
            if (sigma <= 0.0) return
            val radius = (sigma * 4.0).roundToInt().coerceAtLeast(1)
            val twoSigmaSq = 2.0 * sigma * sigma
            for (offset in -radius..radius) {
                val index = center + offset
                if (index in out.indices) {
                    out[index] += amplitude * exp(-(offset * offset) / twoSigmaSq)
                }
            }
        }
    }
}

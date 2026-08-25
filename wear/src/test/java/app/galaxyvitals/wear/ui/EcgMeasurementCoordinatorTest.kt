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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Test

class EcgMeasurementCoordinatorTest {
    @Test
    fun stableContactDoesNotRecordUntilReliableBpmAndRestartsListener() {
        val harness = Harness()
        harness.coordinator.startHardware()

        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Warmup)
        harness.now = 100L
        harness.sensor.emit(batch(sequence = 0, valueMv = 140f))
        harness.now = 400L
        harness.sensor.emit(batch(sequence = 1, valueMv = 140f))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Ready)
        harness.now = 2_101L
        harness.sensor.emit(batch(sequence = 2, valueMv = 140f))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Ready)
        assertThat(harness.recorder.isRecording).isFalse()
        assertThat(harness.coordinator.state.value.liveMv).isNotEmpty()
        assertThat(harness.coordinator.state.value.liveMv.maxOf { kotlin.math.abs(it) }).isLessThan(5f)
        val preflight = harness.coordinator.state.value.waveform
        assertThat(preflight.lastSampleIndex - preflight.firstSampleIndex).isEqualTo(1_499L)

        harness.now = 4_101L
        harness.sensor.emit(batch(sequence = 3))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.CalculatingBpm)
        assertThat(harness.sensor.startCount).isEqualTo(1)
        assertThat(harness.recorder.isRecording).isFalse()
        assertThat(harness.coordinator.state.value.status).isEqualTo("Calculating heart rate…")

        streamUntilCapture(harness, startSequence = 4)
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.StartingCapture)
        assertThat(harness.sensor.startCount).isEqualTo(2)
        assertThat(harness.recorder.isRecording).isFalse()

        holdUntilRecording(harness)
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)
        assertThat(harness.foregroundAcquires).isEqualTo(1)
        assertThat(harness.recorder.isRecording).isTrue()
        assertThat(harness.recorder.sampleCount).isGreaterThan(0)
        assertThat(harness.coordinator.state.value.hrBpm).isNotNull()
    }

    @Test
    fun missingFingerContactAsksToTouchTheButton() {
        val harness = Harness()
        harness.coordinator.startHardware()
        harness.now = 100L
        harness.sensor.emit(batch(sequence = 0, leadOff = 1))

        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.LeadOff)
        assertThat(harness.coordinator.state.value.status).isEqualTo("Touch the button")
    }

    @Test
    fun contactMustRemainStableForFullDebounce() {
        val harness = Harness()
        harness.coordinator.startHardware()
        harness.now = 100L
        harness.sensor.emit(batch(sequence = 0))
        harness.now = 250L
        harness.sensor.emit(batch(sequence = 1, leadOff = 1))
        harness.now = 300L
        harness.sensor.emit(batch(sequence = 2))
        harness.now = 700L
        harness.sensor.emit(batch(sequence = 3))

        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Ready)
        assertThat(harness.foregroundAcquires).isEqualTo(0)
        assertThat(harness.recorder.isRecording).isFalse()
    }

    @Test
    fun recordingFailureIsTerminalUntilRetryAndStaleCallbacksAreIgnored() {
        val harness = Harness()
        startRecording(harness)
        val captureListener = harness.sensor.listeners.lastIndex
        val preflightListener = 0
        harness.sensor.emit(captureListener, batch(sequence = 1, leadOff = 1))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Failed)
        assertThat(harness.sensor.startCount).isEqualTo(2)
        assertThat(harness.sensor.closeCount).isEqualTo(2)
        assertThat(harness.sensor.stopCount).isEqualTo(1)
        assertThat(harness.sensor.disconnectCount).isEqualTo(1)
        assertThat(harness.foregroundCloses).isEqualTo(1)

        harness.sensor.emit(captureListener, batch(sequence = 2))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Failed)
        assertThat(harness.sensor.startCount).isEqualTo(2)

        harness.coordinator.retry()
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Warmup)
        assertThat(harness.sensor.startCount).isEqualTo(3)
        harness.sensor.emit(preflightListener, batch(sequence = 5, leadOff = 1))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Warmup)
    }

    @Test
    fun stabilizingSensorDoesNotPublishBpm() {
        val harness = Harness()
        harness.coordinator.startHardware()
        harness.now = 100L
        harness.sensor.emit(batch(sequence = 0))
        harness.now = 2_101L
        harness.sensor.emit(batch(sequence = 1, samples = syntheticQrs(seconds = 3.0, bpm = 72.0)))
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
        streamQrs(harness, seconds = 10.0, bpm = 72, startSequence = harness.nextSequence)

        val bpm = harness.coordinator.state.value.hrBpm
        assertThat(bpm).isNotNull()
        assertThat(kotlin.math.abs(bpm!! - 72)).isAtMost(8)
        assertThat(harness.coordinator.state.value.bpm.availability)
            .isEqualTo(LiveBpmAvailability.RELIABLE)
        assertThat(harness.coordinator.state.value.bpm.estimate!!.source).isEqualTo(BpmSource.ECG)
        assertThat(harness.coordinator.state.value.bpm.estimate!!.epoch).isEqualTo(BpmEpoch.CAPTURE)
    }

    @Test
    fun recordingPublishesLiveBpmFromSparsePpgCorroboration() {
        val harness = Harness()
        startRecording(harness)
        streamQrs(harness, seconds = 10.0, bpm = 72, startSequence = harness.nextSequence, includePpg = true)

        val bpm = harness.coordinator.state.value.hrBpm
        assertThat(bpm).isNotNull()
        assertThat(kotlin.math.abs(bpm!! - 72)).isAtMost(8)
        assertThat(harness.coordinator.state.value.bpm.estimate!!.source)
            .isEqualTo(BpmSource.ECG_PPG_CORROBORATED)
    }

    @Test
    fun recorderKeepsRawSamplesOnRightWrist() {
        val harness = Harness(wrist = Wrist.RIGHT)
        reachStartingCapture(harness)
        val nextSequence = holdUntilRecording(harness)
        val raw = FloatArray(10) { index -> if (index == 0) 1.5f else 0.12f }
        harness.now += 20L
        harness.sensor.emit(batch(sequence = nextSequence, samples = raw))

        val recorded = harness.recorder.finish("watch")
        val parsed = EcgCsvParser.parseBytes(
            recorded.gzip,
            gzip = true,
            sessionIdHint = requireNotNull(harness.coordinator.state.value.sessionId),
        )
        assertThat(parsed.signFactor).isEqualTo(EcgWearContract.signFactorFor(Wrist.RIGHT))
        assertThat(parsed.samples.last().valueMv).isEqualTo(0.12f)
        assertThat(parsed.samples[parsed.samples.lastIndex - 9].valueMv).isEqualTo(1.5f)
    }

    @Test
    fun displayLiveMvFollowsWristSignFactor() {
        val left = Harness(wrist = Wrist.LEFT)
        val right = Harness(wrist = Wrist.RIGHT)
        val qrs = syntheticQrs(seconds = 3.0, bpm = 72.0)
        startRecording(left)
        startRecording(right)
        streamPrepared(left, qrs, startSequence = left.nextSequence, includePpg = false)
        streamPrepared(right, qrs, startSequence = right.nextSequence, includePpg = false)

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
        var sequence = harness.nextSequence
        harness.now += 100L
        harness.sensor.emit(
            batch(
                sequence = sequence,
                samples = FloatArray(5) { 0.1f },
                ppgGreen = sparsePpg(intArrayOf(0), startMs = 1_000L + sequence * 20L),
            ),
        )
        sequence += 1
        harness.now += 100L
        harness.sensor.emit(
            batch(
                sequence = sequence,
                samples = FloatArray(10) { 0.1f },
                ppgGreen = sparsePpg(intArrayOf(0, 5), startMs = 1_000L + sequence * 20L),
            ),
        )
        sequence += 1
        harness.now += 100L
        harness.sensor.emit(
            batch(
                sequence = sequence,
                samples = FloatArray(5) { 0.1f },
                ppgGreen = sparsePpg(intArrayOf(0), startMs = 1_000L + sequence * 20L),
            ),
        )

        assertThat(harness.coordinator.liveEcgProcessor.livePpg.map { it.ecgSampleIndex })
            .containsExactly(startIndex, startIndex + 5L, startIndex + 10L, startIndex + 15L)
            .inOrder()
    }

    @Test
    fun samsungBatchTimestampsStayOneDisplaySegmentAndKeepCausalFilter() {
        val uniform = LiveEcgProcessor()
        val samsung = LiveEcgProcessor()
        val batchCount = 8
        val batchSize = 10
        val values = FloatArray(batchCount * batchSize) { index ->
            140f + 0.8f * sin(2 * PI * 5.0 * index / 500.0).toFloat()
        }
        for (batchIndex in 0 until batchCount) {
            val start = batchIndex * batchSize
            val chunk = values.copyOfRange(start, start + batchSize)
            val sequence = batchIndex
            uniform.append(
                batch(
                    sequence = sequence,
                    samples = chunk,
                    timestampStartMs = 1_000L + start * 2L,
                ),
            )
            val batchTs = 1_000L + batchIndex * 20L
            samsung.append(
                EcgBatch(
                    samplesMv = chunk,
                    sensorTimestampsMs = LongArray(batchSize) { batchTs },
                    sequence = sequence and 0xff,
                    leadOff = 0,
                    minThresholdMv = -5f,
                    maxThresholdMv = 5f,
                    sampleFlags = IntArray(batchSize),
                ),
            )
        }

        val samsungPoints = samsung.waveformFrame(50L).points
        val uniformValues = uniform.waveformFrame(50L).points.map { it.valueMv }
        assertThat(samsungPoints.count { it.startsNewSegment }).isEqualTo(1)
        assertThat(samsungPoints.first().startsNewSegment).isTrue()
        assertThat(samsungPoints.drop(1).none { it.startsNewSegment }).isTrue()
        assertThat(samsungPoints).hasSize(uniformValues.size)
        samsungPoints.indices.forEach { index ->
            assertThat(samsungPoints[index].valueMv).isWithin(1e-5f).of(uniformValues[index])
        }
        val laterBatchStarts = (1 until batchCount).map {
            kotlin.math.abs(samsungPoints[it * batchSize].valueMv)
        }
        assertThat(laterBatchStarts.maxOrNull()!!).isGreaterThan(0.2f)
    }

    @Test
    fun displayWindowCapsAt1500AndAnalysisWindowCapsAt5000() {
        val harness = Harness()
        startRecording(harness)
        streamQrs(harness, seconds = 12.0, bpm = 72, startSequence = harness.nextSequence)

        val waveform = harness.coordinator.state.value.waveform
        assertThat(harness.coordinator.state.value.liveMv.size)
            .isEqualTo(LiveEcgProcessor.DISPLAY_WINDOW_SAMPLES)
        assertThat(waveform.points.size).isEqualTo(LiveEcgProcessor.DISPLAY_WINDOW_SAMPLES)
        assertThat(waveform.lastSampleIndex - waveform.firstSampleIndex).isEqualTo(1_499L)
        assertThat(harness.coordinator.liveEcgProcessor.analysisSamples.size)
            .isEqualTo(LiveEcgProcessor.ANALYSIS_WINDOW_SAMPLES)
        assertThat(harness.coordinator.state.value.hrBpm).isNotNull()
    }

    @Test
    fun noiseWithoutReliableBpmTimesOutAt15SecondsWithoutAFile() {
        val harness = Harness()
        harness.coordinator.startHardware()
        harness.now = 100L
        harness.sensor.emit(batch(sequence = 0, samples = FloatArray(10) { 0.02f }))
        var sequence = 1
        while (harness.now < 15_200L) {
            harness.now += 20L
            harness.sensor.emit(
                batch(sequence = sequence, samples = FloatArray(10) { 0.02f * ((sequence % 3) - 1) }),
            )
            sequence += 1
        }
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Failed)
        assertThat(harness.coordinator.state.value.error)
            .isEqualTo("Could not measure heart rate. Please try again.")
        assertThat(harness.recorder.isRecording).isFalse()
        assertThat(harness.recorder.sampleCount).isEqualTo(0)
        assertThat(harness.savedGzip).isNull()
    }

    @Test
    fun preflightCallbacksAfterRestartAreDropped() {
        val harness = Harness()
        startRecording(harness)
        val preflight = 0
        val before = harness.recorder.sampleCount
        harness.sensor.emit(preflight, batch(sequence = 99, samples = FloatArray(10) { 9f }))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)
        assertThat(harness.recorder.sampleCount).isEqualTo(before)
        assertThat(harness.coordinator.liveEcgProcessor.analysisSamples.toList()).doesNotContain(9f)
    }

    @Test
    fun captureWithoutFreshBpmFailsAt12Seconds() {
        val harness = Harness()
        startRecording(harness)
        val started = harness.now
        var sequence = harness.nextSequence
        val noise = FloatArray(10) { 0.01f }
        while (harness.now < started + 12_000L) {
            harness.now += 20L
            harness.sensor.emit(batch(sequence = sequence, samples = noise))
            sequence += 1
        }
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Failed)
        assertThat(harness.coordinator.state.value.error)
            .isEqualTo("Signal unstable. Please try again.")
        assertThat(harness.savedGzip).isNull()
    }

    @Test
    fun completeCaptureWrites15000RawSamplesAt500Hz() {
        val harness = Harness()
        reachStartingCapture(harness)
        val nextSequence = holdUntilRecording(harness)
        val qrs = syntheticQrs(seconds = 30.0, bpm = 72.0)
        streamPrepared(
            harness,
            qrs,
            startSequence = nextSequence,
            includePpg = false,
            batchSize = 10,
            stopWhen = {
                harness.coordinator.state.value.phase in setOf(
                    MeasurePhase.Saving,
                    MeasurePhase.Success,
                    MeasurePhase.Failed,
                )
            },
        )
        assertThat(harness.recorder.sampleCount).isEqualTo(0)
        assertThat(harness.coordinator.state.value.phase)
            .isIn(setOf(MeasurePhase.Saving, MeasurePhase.Success))
        val gzip = requireNotNull(harness.savedGzip)
        assertThat(harness.pushedGzip).isEqualTo(gzip)
        val parsed = EcgCsvParser.parseBytes(
            gzip,
            gzip = true,
            sessionIdHint = requireNotNull(harness.coordinator.state.value.sessionId),
        )
        assertThat(parsed.samples).hasSize(15_000)
        assertThat(parsed.captureSource.name).isEqualTo("HARDWARE")
        assertThat(parsed.schemaVersion).isEqualTo(2)
        val duration = parsed.samples.last().relMs - parsed.samples.first().relMs
        assertThat(duration).isEqualTo(29_998L)
        val hz = 14_999.0 * 1000.0 / duration
        assertThat(hz).isWithin(5.0).of(500.0)
    }

    @Test
    fun waveformUiEmitsAtMost10HzUnderSamsungCallbackLoad() {
        val harness = Harness()
        startRecording(harness)
        val startStates = harness.uiStates.size
        val logsBefore = harness.transitionLogs.size
        val startNow = harness.now
        var sequence = harness.nextSequence
        repeat(80) {
            harness.now += 10L
            harness.sensor.emit(batch(sequence = sequence, samples = FloatArray(5) { 0.1f }))
            sequence += 1
        }
        assertThat(harness.now - startNow).isEqualTo(800L)
        val waveformUpdates = harness.uiStates.drop(startStates).zipWithNext().count { (a, b) ->
            a.waveform !== b.waveform
        }
        assertThat(waveformUpdates).isAtMost(10)
        assertThat(harness.transitionLogs.size).isEqualTo(logsBefore)
    }

    @Test
    fun blockedBpmWorkerDoesNotDropCaptureSamples() {
        val gate = CountDownLatch(1)
        val harness = Harness(compute = GatedDispatcher(gate))
        startRecording(harness)
        (harness.computeDispatcher as GatedDispatcher).block = true
        val before = harness.recorder.sampleCount
        var sequence = harness.nextSequence
        repeat(40) {
            harness.now += 20L
            harness.sensor.emit(batch(sequence = sequence, samples = FloatArray(10) { 0.12f }))
            sequence += 1
        }
        assertThat(harness.recorder.sampleCount).isEqualTo(before + 400)
        gate.countDown()
        assertThat(gate.await(1, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun contactWaitTimesOutAt10SecondsWithoutContact() {
        val harness = Harness()
        harness.coordinator.startHardware()
        harness.now = 100L
        harness.sensor.emit(batch(sequence = 0, leadOff = 1))
        harness.now = 10_100L
        harness.sensor.emit(batch(sequence = 1, leadOff = 1))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Failed)
        assertThat(harness.recorder.sampleCount).isEqualTo(0)
    }

    @Test
    fun firstCaptureBatchLeadOffStaysInStartingCaptureWithoutRecorder() {
        val harness = Harness()
        reachStartingCapture(harness)
        harness.now += 20L
        harness.sensor.emit(batch(sequence = 0, leadOff = 1))

        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.StartingCapture)
        assertThat(harness.coordinator.state.value.status).isEqualTo("Touch the button")
        assertThat(harness.recorder.isRecording).isFalse()
        assertThat(harness.recorder.sampleCount).isEqualTo(0)
        assertThat(harness.foregroundAcquires).isEqualTo(0)
        assertThat(harness.savedGzip).isNull()
        assertThat(harness.pushedGzip).isNull()
        assertThat(harness.acquisitionLogs).isNotEmpty()
        assertThat(harness.acquisitionLogs.joinToString("\n")).contains("phase=StartingCapture")
        assertThat(harness.acquisitionLogs.joinToString("\n")).contains("leadOff=1")
        assertThat(harness.acquisitionLogs.joinToString("\n")).contains("generation=")
        assertThat(harness.acquisitionLogs.none { log -> log.contains("samplesMv") }).isTrue()
    }

    @Test
    fun captureRearmRecordsOnlyBatchesAfterOneSecondContact() {
        val harness = Harness()
        reachStartingCapture(harness)
        harness.now += 20L
        harness.sensor.emit(batch(sequence = 0, leadOff = 1))
        val contactStart = harness.now + 20L
        harness.now = contactStart
        harness.sensor.emit(batch(sequence = 1, samples = FloatArray(10) { 99f }))
        harness.now = contactStart + 980L
        harness.sensor.emit(batch(sequence = 2, samples = FloatArray(10) { 99f }))

        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.StartingCapture)
        assertThat(harness.recorder.isRecording).isFalse()
        assertThat(harness.foregroundAcquires).isEqualTo(0)

        harness.now = contactStart + 1_000L
        val firstRecorded = FloatArray(10) { index -> if (index == 0) 1.5f else 0.12f }
        harness.sensor.emit(batch(sequence = 3, samples = firstRecorded))

        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)
        assertThat(harness.foregroundAcquires).isEqualTo(1)
        assertThat(harness.recorder.isRecording).isTrue()
        assertThat(harness.recorder.sampleCount).isEqualTo(10)
        val recorded = harness.recorder.finish("watch")
        val parsed = EcgCsvParser.parseBytes(
            recorded.gzip,
            gzip = true,
            sessionIdHint = requireNotNull(harness.coordinator.state.value.sessionId),
        )
        assertThat(parsed.samples[0].valueMv).isEqualTo(1.5f)
        assertThat(parsed.samples.map { it.valueMv }).doesNotContain(99f)
        assertThat(parsed.captureSource.name).isEqualTo("HARDWARE")
    }

    @Test
    fun captureContactDebounceResetsWhenLeadOffReturns() {
        val harness = Harness()
        reachStartingCapture(harness)
        val t0 = harness.now + 20L
        harness.now = t0
        harness.sensor.emit(batch(sequence = 0))
        harness.now = t0 + 500L
        harness.sensor.emit(batch(sequence = 1))
        harness.now = t0 + 600L
        harness.sensor.emit(batch(sequence = 2, leadOff = 1))
        val t1 = t0 + 700L
        harness.now = t1
        harness.sensor.emit(batch(sequence = 3))
        harness.now = t1 + 800L
        harness.sensor.emit(batch(sequence = 4))

        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.StartingCapture)
        assertThat(harness.coordinator.state.value.status).isEqualTo("Starting capture…")
        assertThat(harness.recorder.isRecording).isFalse()
        assertThat(harness.foregroundAcquires).isEqualTo(0)

        harness.now = t1 + 1_000L
        harness.sensor.emit(batch(sequence = 5))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)
        assertThat(harness.recorder.isRecording).isTrue()
    }

    @Test
    fun captureContactTimeoutAfter10SecondsCleansUpWithoutFile() {
        val harness = Harness()
        reachStartingCapture(harness)
        val captureListener = harness.sensor.listeners.lastIndex
        harness.now += 20L
        harness.sensor.emit(batch(sequence = 0, leadOff = 1))
        harness.now += 10_100L
        harness.sensor.emit(batch(sequence = 1, leadOff = 1))

        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Failed)
        assertThat(harness.coordinator.state.value.error).isEqualTo(
            "ECG contact was not detected in time. Keep touching the button and try again.",
        )
        assertThat(harness.recorder.isRecording).isFalse()
        assertThat(harness.recorder.sampleCount).isEqualTo(0)
        assertThat(harness.savedGzip).isNull()
        assertThat(harness.pushedGzip).isNull()
        assertThat(harness.foregroundAcquires).isEqualTo(0)
        assertThat(harness.sensor.startCount).isEqualTo(2)
        assertThat(harness.sensor.closeCount).isEqualTo(2)
        assertThat(harness.sensor.stopCount).isEqualTo(1)
        assertThat(harness.sensor.disconnectCount).isEqualTo(1)

        harness.sensor.emit(captureListener, batch(sequence = 2))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Failed)
    }

    private class Harness(
        wrist: Wrist = Wrist.LEFT,
        compute: CoroutineDispatcher = Dispatchers.Unconfined,
    ) {
        val sensor = FakeSensor()
        val recorder = EcgSessionRecorder()
        var now = 1L
        var nextSequence = 0
        var foregroundAcquires = 0
        var foregroundCloses = 0
        var savedGzip: ByteArray? = null
        var pushedGzip: ByteArray? = null
        val transitionLogs = ArrayList<String>()
        val acquisitionLogs = ArrayList<String>()
        val uiStates = ArrayList<MeasureUiState>()
        val computeDispatcher = compute
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
            save = { _, gzip -> savedGzip = gzip },
            pushToPhone = { _, gzip -> pushedGzip = gzip },
            watchInfo = { "watch" },
            offBodyFactory = { FakeOffBody() },
            mainDispatcher = Dispatchers.Unconfined,
            computeDispatcher = compute,
            elapsedRealtime = { now },
            wallClock = { 1_700_000_000_000L + now },
            transitionLogger = { transitionLogs += it },
            bpmLogger = {},
            acquisitionLogger = { acquisitionLogs += it },
        )

        init {
            scope.launch(Dispatchers.Unconfined) {
                coordinator.state.collect { uiStates += it }
            }
        }
    }

    private class GatedDispatcher(
        private val gate: CountDownLatch,
    ) : CoroutineDispatcher() {
        @Volatile
        var block = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (this.block) {
                Thread {
                    gate.await()
                    block.run()
                }.start()
            } else {
                block.run()
            }
        }
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

        fun emit(batch: EcgBatch) {
            emit(listeners.lastIndex, batch)
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
        private fun reachStartingCapture(harness: Harness) {
            harness.coordinator.startHardware()
            harness.now = 100L
            harness.sensor.emit(batch(sequence = 0))
            streamUntilCapture(harness, startSequence = 1)
            assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.StartingCapture)
            assertThat(harness.sensor.startCount).isEqualTo(2)
        }

        private fun holdUntilRecording(harness: Harness, startSequence: Int = 0): Int {
            var sequence = startSequence
            val started = harness.now
            while (harness.coordinator.state.value.phase == MeasurePhase.StartingCapture) {
                harness.now += 20L
                harness.sensor.emit(batch(sequence = sequence))
                sequence += 1
                check(harness.now - started < 2_500L) { "capture re-arm did not enter Recording" }
            }
            harness.nextSequence = sequence
            return sequence
        }

        private fun startRecording(harness: Harness) {
            reachStartingCapture(harness)
            holdUntilRecording(harness)
            assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)
            assertThat(harness.recorder.sampleCount).isGreaterThan(0)
            assertThat(harness.coordinator.state.value.hrBpm).isNotNull()
        }

        private fun streamUntilCapture(harness: Harness, startSequence: Int) {
            val listenerIndex = harness.sensor.listeners.lastIndex
            val qrs = syntheticQrs(seconds = 12.0, bpm = 72.0)
            streamPrepared(
                harness,
                qrs,
                startSequence,
                includePpg = false,
                batchSize = 10,
                listenerIndex = listenerIndex,
                stopWhen = { harness.coordinator.state.value.phase == MeasurePhase.StartingCapture },
            )
            assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.StartingCapture)
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
            batchSize: Int = 10,
            listenerIndex: Int = harness.sensor.listeners.lastIndex,
            stopWhen: () -> Boolean = { false },
        ) {
            val ppg = if (includePpg) {
                LiveBpmEstimatorTest.syntheticSparsePpg(qrs.size, bpm)
            } else {
                null
            }
            var sequence = startSequence
            var offset = 0
            while (offset < qrs.size) {
                if (stopWhen()) return
                val count = minOf(batchSize, qrs.size - offset)
                harness.now += count * 2L
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
                    listenerIndex,
                    batch(
                        sequence = sequence,
                        samples = samples,
                        timestampStartMs = 1_000L + sequence * 20L,
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

package app.galaxyvitals.wear.ui

import app.galaxyvitals.data.protocol.EcgCsvParser
import app.galaxyvitals.data.protocol.EcgSyncSemantics
import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.domain.Wrist
import app.galaxyvitals.wear.capture.EcgSessionRecorder
import android.app.Activity
import app.galaxyvitals.wear.sensors.EcgBatch
import app.galaxyvitals.wear.sensors.EcgSensor
import app.galaxyvitals.wear.sensors.EcgSensorError
import app.galaxyvitals.wear.sensors.EcgSensorErrorCode
import app.galaxyvitals.wear.sensors.EcgSubscription
import app.galaxyvitals.wear.sensors.SensorIssue
import app.galaxyvitals.wear.sensors.SensorIssueCode
import app.galaxyvitals.wear.sensors.SensorRecovery
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Test

class EcgMeasurementCoordinatorTest {
    @Test
    fun successfulCaptureUsesOneBoundedProbeAndOneCaptureListener() {
        val harness = Harness()
        startRecording(harness)
        assertThat(harness.sensor.startCount).isEqualTo(2)
        assertThat(harness.sensor.listeners.first().setToCloseMs).isAtMost(30_000L)
        assertThat(harness.sensor.lastMaxDurationMs).isEqualTo(30_000L)
        assertThat(harness.foregroundAcquires).isEqualTo(1)
        assertThat(harness.recorder.isRecording).isTrue()

        // SM-L350 reports one transient lead-off batch whenever the capture listener is reopened.
        harness.sensor.emit(batch(sequence = 0, leadOff = 5))
        assertThat(harness.recorder.sampleCount).isEqualTo(0)
        harness.nextSequence = 1
        streamUntilTerminal(harness, samples = FloatArray(15_000) { 0.12f })
        assertThat(harness.sensor.startCount).isEqualTo(2)
        assertThat(harness.sensor.closeCount).isEqualTo(2)
        assertThat(harness.coordinator.state.value.phase)
            .isIn(setOf(MeasurePhase.Saving, MeasurePhase.Success))
        assertThat(requireNotNull(harness.savedGzip)).isEqualTo(harness.pushedGzip)
    }

    @Test
    fun listenerClosesWithin30000MsOnSuccessCancelErrorAndDeadline() {
        val success = Harness()
        startRecording(success)
        val successStart = success.sensor.listeners.last().startedAtMs
        streamUntilTerminal(success, samples = FloatArray(15_000) { 0.12f })
        assertThat(success.sensor.listeners.last().setToCloseMs).isAtMost(30_000L)
        assertThat(success.sensor.listeners.last().closedAtMs!! - successStart).isAtMost(30_000L)
        assertThat(success.sensor.closeCount).isEqualTo(2)

        val cancelled = Harness()
        startRecording(cancelled)
        cancelled.advance(100L)
        cancelled.coordinator.cancel()
        assertThat(cancelled.coordinator.state.value.phase).isEqualTo(MeasurePhase.Failed)
        assertThat(cancelled.sensor.startCount).isEqualTo(2)
        assertThat(cancelled.sensor.closeCount).isEqualTo(2)
        assertThat(cancelled.sensor.listeners.last().setToCloseMs).isAtMost(30_000L)

        val errored = Harness()
        startRecording(errored)
        errored.advance(50L)
        errored.sensor.emitError(EcgSensorError(EcgSensorErrorCode.TRACKER, "tracker failed"))
        assertThat(errored.coordinator.state.value.phase).isEqualTo(MeasurePhase.Failed)
        assertThat(errored.sensor.startCount).isEqualTo(2)
        assertThat(errored.sensor.closeCount).isEqualTo(2)
        assertThat(errored.sensor.listeners.last().setToCloseMs).isAtMost(30_000L)

        val deadline = Harness()
        startRecording(deadline)
        val started = deadline.sensor.listeners.last().startedAtMs
        deadline.now = started + 30_000L
        deadline.sensor.fireDeadline()
        assertThat(deadline.coordinator.state.value.phase).isEqualTo(MeasurePhase.Failed)
        assertThat(deadline.transitionLogs.joinToString()).contains("INCOMPLETE_CAPTURE")
        assertThat(deadline.sensor.startCount).isEqualTo(2)
        assertThat(deadline.sensor.closeCount).isEqualTo(2)
        assertThat(deadline.sensor.listeners.last().setToCloseMs).isAtMost(30_000L)
        assertThat(deadline.savedGzip).isNull()
    }

    @Test
    fun fifteenThousandSamplesSucceedAnd14995FailIncompleteCapture() {
        val complete = Harness()
        startRecording(complete)
        streamUntilTerminal(complete, samples = FloatArray(15_000) { 0.11f })
        assertThat(complete.coordinator.state.value.phase)
            .isIn(setOf(MeasurePhase.Saving, MeasurePhase.Success))
        val gzip = requireNotNull(complete.savedGzip)
        val parsed = EcgCsvParser.parseBytes(
            gzip,
            gzip = true,
            sessionIdHint = requireNotNull(complete.coordinator.state.value.sessionId),
        )
        assertThat(parsed.samples).hasSize(15_000)
        assertThat(complete.sensor.startCount).isEqualTo(2)

        val incomplete = Harness()
        startRecording(incomplete)
        streamPrepared(
            incomplete,
            FloatArray(14_995) { 0.11f },
            startSequence = 0,
            includePpg = false,
            batchSize = 5,
        )
        assertThat(incomplete.recorder.sampleCount).isEqualTo(14_995)
        incomplete.sensor.fireDeadline()
        assertThat(incomplete.coordinator.state.value.phase).isEqualTo(MeasurePhase.Failed)
        assertThat(incomplete.transitionLogs.joinToString()).contains("INCOMPLETE_CAPTURE")
        assertThat(incomplete.savedGzip).isNull()
        assertThat(incomplete.sensor.closeCount).isEqualTo(2)
    }

    @Test
    fun contactProbeWaitsForValidContactBeforeStartingCountdown() {
        val harness = Harness()
        harness.coordinator.startHardware()
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.WaitingForContact)
        assertThat(harness.coordinator.state.value.status).isEqualTo("Touch the sensor to begin")
        assertThat(harness.sensor.startCount).isEqualTo(1)

        harness.sensor.emit(batch(sequence = 0, leadOff = 5))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.WaitingForContact)
        assertThat(harness.recorder.isRecording).isFalse()
        assertThat(harness.sensor.closeCount).isEqualTo(0)

        harness.sensor.emit(batch(sequence = 1, leadOff = 0))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.ArmedCountdown)
        assertThat(harness.coordinator.state.value.remainingSec).isEqualTo(3)
        assertThat(harness.sensor.startCount).isEqualTo(1)
    }

    @Test
    fun contactLossDuringCountdownReturnsToWaitingAndCancelsOldCountdown() {
        val harness = Harness()
        harness.coordinator.startHardware()
        harness.sensor.emit(batch(sequence = 0, leadOff = 0))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.ArmedCountdown)

        harness.advance(1_000L)
        harness.sensor.emit(batch(sequence = 1, leadOff = 5))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.WaitingForContact)
        assertThat(harness.recorder.isRecording).isFalse()
        harness.advance(3_000L)
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.WaitingForContact)
        assertThat(harness.sensor.startCount).isEqualTo(1)

        harness.sensor.emit(batch(sequence = 2, leadOff = 0))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.ArmedCountdown)
        harness.advance(3_000L)
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)
        assertThat(harness.sensor.startCount).isEqualTo(2)
    }

    @Test
    fun contactProbeTimesOutAndClosesWithinOnDemandLimit() {
        val harness = Harness()
        harness.coordinator.startHardware()
        val startedAt = harness.sensor.listeners.single().startedAtMs
        harness.sensor.emit(batch(sequence = 0, leadOff = 5))

        harness.advance(25_000L)

        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Failed)
        assertThat(harness.transitionLogs.joinToString()).contains("CONTACT_TIMEOUT")
        assertThat(harness.sensor.startCount).isEqualTo(1)
        assertThat(harness.sensor.closeCount).isEqualTo(1)
        assertThat(harness.sensor.listeners.single().closedAtMs!! - startedAt).isAtMost(30_000L)
        assertThat(harness.recorder.isRecording).isFalse()
    }

    @Test
    fun recordingSkipsTransientFirstLeadOffThenFailsClosedOnLaterContactLoss() {
        val harness = Harness()
        startRecording(harness)
        assertThat(harness.recorder.isRecording).isTrue()
        assertThat(harness.recorder.sampleCount).isEqualTo(0)
        harness.sensor.emit(batch(sequence = 0, leadOff = 1))

        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)
        assertThat(harness.coordinator.state.value.status).isEqualTo("Confirming sensor contact…")
        assertThat(harness.recorder.sampleCount).isEqualTo(0)
        assertThat(harness.sensor.startCount).isEqualTo(2)
        assertThat(harness.sensor.closeCount).isEqualTo(1)
        assertThat(harness.savedGzip).isNull()

        harness.sensor.emit(batch(sequence = 1, leadOff = 0))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)
        assertThat(harness.coordinator.state.value.status).isEqualTo("Recording")
        assertThat(harness.recorder.sampleCount).isEqualTo(10)

        harness.sensor.emit(batch(sequence = 2, leadOff = 5))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Failed)
        assertThat(harness.transitionLogs.joinToString()).contains("CONTACT_LOSS")
        assertThat(harness.sensor.closeCount).isEqualTo(2)
        assertThat(harness.sensor.stopCount).isEqualTo(1)
        assertThat(harness.sensor.disconnectCount).isEqualTo(1)
        assertThat(harness.foregroundCloses).isEqualTo(1)
        assertThat(harness.recorder.isRecording).isFalse()
        assertThat(harness.recorder.sampleCount).isEqualTo(0)
        assertThat(harness.savedGzip).isNull()
        assertThat(harness.acquisitionLogs.joinToString("\n")).contains("leadOff=1")
        assertThat(harness.acquisitionLogs.joinToString("\n")).contains("leadOff=5")
        assertThat(harness.acquisitionLogs.joinToString("\n")).contains("generation=")
        assertThat(harness.acquisitionLogs.none { log -> log.contains("samplesMv") }).isTrue()
    }

    @Test
    fun missingBpmDoesNotAbortSave() {
        val harness = Harness()
        startRecording(harness)
        streamUntilTerminal(harness, samples = FloatArray(15_000) { 0.02f * ((it % 3) - 1) })
        assertThat(harness.coordinator.state.value.phase)
            .isIn(setOf(MeasurePhase.Saving, MeasurePhase.Success))
        assertThat(harness.savedGzip).isNotNull()
        assertThat(harness.coordinator.state.value.hrBpm).isNull()
        assertThat(harness.sensor.startCount).isEqualTo(2)
    }

    @Test
    fun ppgDisagreementDoesNotAbortSave() {
        val harness = Harness()
        startRecording(harness)
        val qrs = syntheticQrs(seconds = 30.0, bpm = 72.0)
        streamPrepared(
            harness,
            qrs,
            startSequence = 0,
            includePpg = true,
            bpm = 110,
            stopWhen = {
                harness.coordinator.state.value.phase in setOf(
                    MeasurePhase.Saving,
                    MeasurePhase.Success,
                    MeasurePhase.Failed,
                )
            },
        )
        assertThat(harness.coordinator.state.value.phase)
            .isIn(setOf(MeasurePhase.Saving, MeasurePhase.Success))
        assertThat(harness.savedGzip).isNotNull()
        assertThat(harness.sensor.startCount).isEqualTo(2)
    }

    @Test
    fun staleCallbacksAfterRetryOrCancelAreIgnored() {
        val cancelled = Harness()
        startRecording(cancelled)
        val cancelledListener = 1
        cancelled.coordinator.cancel()
        assertThat(cancelled.coordinator.state.value.phase).isEqualTo(MeasurePhase.Failed)
        cancelled.sensor.emit(cancelledListener, batch(sequence = 2, samples = FloatArray(10) { 9f }))
        assertThat(cancelled.coordinator.state.value.phase).isEqualTo(MeasurePhase.Failed)
        assertThat(cancelled.sensor.startCount).isEqualTo(2)

        val retried = Harness()
        startRecording(retried)
        val firstListener = 1
        retried.sensor.emit(batch(sequence = 0, leadOff = 0))
        retried.sensor.emit(batch(sequence = 1, leadOff = 1))
        assertThat(retried.coordinator.state.value.phase).isEqualTo(MeasurePhase.Failed)
        retried.sensor.emit(firstListener, batch(sequence = 2, samples = FloatArray(10) { 8f }))
        assertThat(retried.coordinator.state.value.phase).isEqualTo(MeasurePhase.Failed)

        retried.coordinator.retry()
        assertThat(retried.coordinator.state.value.phase).isEqualTo(MeasurePhase.WaitingForContact)
        assertThat(retried.sensor.startCount).isEqualTo(3)
        retried.sensor.emit(firstListener, batch(sequence = 5, samples = FloatArray(10) { 7f }))
        assertThat(retried.coordinator.state.value.phase).isEqualTo(MeasurePhase.WaitingForContact)
        retried.sensor.emit(batch(sequence = 0, leadOff = 0))
        assertThat(retried.coordinator.state.value.phase).isEqualTo(MeasurePhase.ArmedCountdown)
        assertThat(retried.recorder.sampleCount).isEqualTo(0)

        retried.advance(3_000L)
        assertThat(retried.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)
        assertThat(retried.sensor.startCount).isEqualTo(4)
        val before = retried.recorder.sampleCount
        retried.sensor.emit(firstListener, batch(sequence = 6, samples = FloatArray(10) { 9f }))
        assertThat(retried.recorder.sampleCount).isEqualTo(before)
        assertThat(retried.coordinator.liveEcgProcessor.analysisSamples.toList()).doesNotContain(9f)
    }

    @Test
    fun lastBatchOvershootKeepsPrefixCompleting15000() {
        val harness = Harness()
        startRecording(harness)
        streamPrepared(
            harness,
            FloatArray(14_995) { 0.12f },
            startSequence = 0,
            includePpg = false,
            batchSize = 5,
        )
        assertThat(harness.recorder.sampleCount).isEqualTo(14_995)
        val overshoot = FloatArray(10) { index -> (index + 1) * 0.1f }
        harness.now += 20L
        harness.sensor.emit(
            batch(
                sequence = harness.nextSequence,
                samples = overshoot,
                timestampStartMs = 1_000L + 14_995L * 2L,
            ),
        )

        assertThat(harness.coordinator.state.value.phase)
            .isIn(setOf(MeasurePhase.Saving, MeasurePhase.Success))
        assertThat(harness.acquisitionLogs.joinToString("\n")).contains("batchSize=10")
        val gzip = requireNotNull(harness.savedGzip)
        val parsed = EcgCsvParser.parseBytes(
            gzip,
            gzip = true,
            sessionIdHint = requireNotNull(harness.coordinator.state.value.sessionId),
        )
        assertThat(parsed.samples).hasSize(15_000)
        assertThat(parsed.samples.takeLast(5).map { it.valueMv })
            .containsExactly(0.1f, 0.2f, 0.3f, 0.4f, 0.5f)
            .inOrder()
        assertThat(parsed.samples.map { it.valueMv }).doesNotContain(0.6f)
        assertThat(parsed.samples.map { it.valueMv }).doesNotContain(1.0f)
        assertThat(harness.sensor.startCount).isEqualTo(2)
        assertThat(harness.sensor.closeCount).isEqualTo(2)
    }

    @Test
    fun closeClosesListenerAndDoesNotLeaveLiveCapturePhase() {
        val recording = Harness()
        startRecording(recording)
        assertThat(recording.sensor.closeCount).isEqualTo(1)
        recording.coordinator.close()
        assertThat(recording.sensor.closeCount).isEqualTo(2)
        assertThat(recording.coordinator.state.value.phase)
            .isNotIn(
                setOf(
                    MeasurePhase.WaitingForContact,
                    MeasurePhase.ArmedCountdown,
                    MeasurePhase.Recording,
                    MeasurePhase.Connecting,
                ),
            )

        val countdown = Harness()
        countdown.coordinator.startHardware()
        assertThat(countdown.coordinator.state.value.phase).isEqualTo(MeasurePhase.WaitingForContact)
        countdown.sensor.emit(batch(sequence = 0, leadOff = 0))
        assertThat(countdown.coordinator.state.value.phase).isEqualTo(MeasurePhase.ArmedCountdown)
        countdown.coordinator.close()
        assertThat(countdown.sensor.startCount).isEqualTo(1)
        assertThat(countdown.coordinator.state.value.phase)
            .isNotIn(setOf(MeasurePhase.WaitingForContact, MeasurePhase.ArmedCountdown, MeasurePhase.Recording))
        countdown.advance(3_000L)
        assertThat(countdown.sensor.startCount).isEqualTo(1)
        assertThat(countdown.coordinator.state.value.phase)
            .isNotEqualTo(MeasurePhase.Recording)
    }

    @Test
    fun countdownTickAfterCancelDoesNotReviveArmedCountdown() {
        val harness = Harness()
        harness.coordinator.startHardware()
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.WaitingForContact)
        harness.sensor.emit(batch(sequence = 0, leadOff = 0))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.ArmedCountdown)
        harness.coordinator.cancel()
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Failed)
        harness.advance(3_000L)
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Failed)
        assertThat(harness.sensor.startCount).isEqualTo(1)
        assertThat(harness.sensor.closeCount).isEqualTo(1)
    }

    @Test
    fun inFlightCompletingBatchThenDeadlineStillSaves() {
        val acquisition = PauseableDispatcher()
        val harness = Harness(acquisition = acquisition)
        startRecording(harness)
        streamPrepared(
            harness,
            FloatArray(14_990) { 0.12f },
            startSequence = 0,
            includePpg = false,
            batchSize = 10,
        )
        assertThat(harness.recorder.sampleCount).isEqualTo(14_990)
        acquisition.pause()
        harness.now += 20L
        harness.sensor.emit(
            batch(
                sequence = harness.nextSequence,
                samples = FloatArray(10) { 0.12f },
                timestampStartMs = 1_000L + 14_990L * 2L,
            ),
        )
        harness.sensor.fireDeadline()
        acquisition.resume()
        assertThat(harness.coordinator.state.value.phase)
            .isIn(setOf(MeasurePhase.Saving, MeasurePhase.Success))
        assertThat(harness.savedGzip).isNotNull()
        assertThat(harness.transitionLogs.joinToString()).doesNotContain("INCOMPLETE_CAPTURE")
        assertThat(harness.sensor.closeCount).isEqualTo(2)
    }

    @Test
    fun deadlineThenInFlightCompletingBatchStillSaves() {
        val acquisition = PauseableDispatcher()
        val harness = Harness(acquisition = acquisition)
        startRecording(harness)
        streamPrepared(
            harness,
            FloatArray(14_990) { 0.12f },
            startSequence = 0,
            includePpg = false,
            batchSize = 10,
        )
        assertThat(harness.recorder.sampleCount).isEqualTo(14_990)
        acquisition.pause()
        harness.sensor.fireDeadline()
        harness.now += 20L
        harness.sensor.emit(
            batch(
                sequence = harness.nextSequence,
                samples = FloatArray(10) { 0.12f },
                timestampStartMs = 1_000L + 14_990L * 2L,
            ),
        )
        acquisition.resume()
        assertThat(harness.coordinator.state.value.phase)
            .isIn(setOf(MeasurePhase.Saving, MeasurePhase.Success))
        assertThat(harness.savedGzip).isNotNull()
        assertThat(harness.transitionLogs.joinToString()).doesNotContain("INCOMPLETE_CAPTURE")
        assertThat(harness.sensor.closeCount).isEqualTo(2)
    }

    @Test
    fun validContactStartsThreeSecondCountdownBeforeCaptureListener() {
        val harness = Harness()
        harness.coordinator.startHardware()
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.WaitingForContact)
        assertThat(harness.coordinator.state.value.status)
            .isEqualTo("Touch the sensor to begin")
        assertThat(harness.sensor.startCount).isEqualTo(1)
        assertThat(harness.recorder.isRecording).isFalse()
        harness.advance(2_999L)
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.WaitingForContact)
        harness.sensor.emit(batch(sequence = 0, leadOff = 5))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.WaitingForContact)
        harness.sensor.emit(batch(sequence = 1, leadOff = 0))
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.ArmedCountdown)
        assertThat(harness.coordinator.state.value.status).isEqualTo("Starting in")
        harness.advance(2_999L)
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.ArmedCountdown)
        assertThat(harness.sensor.startCount).isEqualTo(1)
        harness.advance(1L)
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)
        assertThat(harness.sensor.startCount).isEqualTo(2)
        assertThat(harness.sensor.closeCount).isEqualTo(1)
        assertThat(harness.sensor.lastMaxDurationMs).isEqualTo(30_000L)
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
        assertThat(harness.coordinator.state.value.bpm.estimate!!.source).isEqualTo(BpmSource.APP_ECG_RR)
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
            .isEqualTo(BpmSource.APP_ECG_RR_PPG_CORROBORATED)
    }

    @Test
    fun recorderKeepsRawSamplesOnRightWrist() {
        val harness = Harness(wrist = Wrist.RIGHT)
        startRecording(harness)
        streamUntilTerminal(
            harness,
            samples = FloatArray(15_000) { index -> if (index == 0) 1.5f else 0.12f },
        )

        val parsed = EcgCsvParser.parseBytes(
            requireNotNull(harness.savedGzip),
            gzip = true,
            sessionIdHint = requireNotNull(harness.coordinator.state.value.sessionId),
        )
        assertThat(parsed.schemaVersion).isEqualTo(3)
        assertThat(parsed.signFactor).isEqualTo(EcgWearContract.signFactorFor(Wrist.RIGHT))
        assertThat(parsed.samples[0].valueMv).isEqualTo(1.5f)
        assertThat(parsed.samples[1].valueMv).isEqualTo(0.12f)
        assertThat(parsed.samples).hasSize(15_000)
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
        val copiesBefore = harness.coordinator.liveEcgProcessor.analysisCopyCount
        var sequence = harness.nextSequence
        repeat(40) {
            harness.now += 20L
            harness.sensor.emit(batch(sequence = sequence, samples = FloatArray(10) { 0.12f }))
            sequence += 1
        }
        assertThat(harness.recorder.sampleCount).isEqualTo(before + 400)
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)
        assertThat(harness.coordinator.liveEcgProcessor.analysisCopyCount - copiesBefore).isEqualTo(1)
        gate.countDown()
        assertThat(gate.await(1, TimeUnit.SECONDS)).isTrue()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (harness.coordinator.bpmComputeCount < 1 && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertThat(harness.coordinator.bpmComputeCount).isEqualTo(1)
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)
    }

    @Test
    fun fiveAndTenPointCallbacksCopyAndComputeAtMostOncePerSecond() {
        val harness = Harness()
        startRecording(harness)
        val start = harness.now
        val copiesBefore = harness.coordinator.liveEcgProcessor.analysisCopyCount
        var sequence = harness.nextSequence
        repeat(200) {
            harness.now += 10L
            harness.sensor.emit(batch(sequence = sequence, samples = FloatArray(5) { 0.11f }))
            sequence += 1
        }
        repeat(100) {
            harness.now += 20L
            harness.sensor.emit(batch(sequence = sequence, samples = FloatArray(10) { 0.11f }))
            sequence += 1
        }
        val elapsed = harness.now - start
        assertThat(elapsed).isEqualTo(4_000L)
        val maxAllowed = (elapsed / 1_000L).toInt() + 1
        val copies = harness.coordinator.liveEcgProcessor.analysisCopyCount - copiesBefore
        assertThat(copies).isGreaterThan(0)
        assertThat(copies).isAtMost(maxAllowed)
        assertThat(harness.coordinator.bpmComputeCount).isGreaterThan(0)
        assertThat(harness.coordinator.bpmComputeCount).isAtMost(maxAllowed)
        assertThat(harness.recorder.sampleCount).isEqualTo(2_000)
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)
    }

    @Test
    fun liveBpmObservationsRecordDisplayedAndAbstainedResultsWithoutChangingCapture() {
        val harness = Harness()
        startRecording(harness)
        assertThat(harness.recorder.liveBpmObservations().map { it.status })
            .contains(LiveBpmAvailability.COLLECTING.name)
        streamQrs(harness, seconds = 10.0, bpm = 72, startSequence = harness.nextSequence)
        val observations = harness.recorder.liveBpmObservations()
        assertThat(observations.size).isGreaterThan(1)
        assertThat(observations.any { it.status == LiveBpmAvailability.RELIABLE.name }).isTrue()
        assertThat(observations.any { it.source == BpmSource.APP_ECG_RR.name }).isTrue()
        assertThat(observations.all { it.atSampleIndex >= 0L }).isTrue()
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)
        assertThat(harness.recorder.isRecording).isTrue()
    }

    @Test
    fun abstainedLiveBpmStillRecordsObservationAndKeepsRecording() {
        val harness = Harness()
        startRecording(harness)
        var sequence = harness.nextSequence
        repeat(100) {
            harness.now += 20L
            harness.sensor.emit(batch(sequence = sequence, samples = FloatArray(10) { 0.02f * ((it % 3) - 1) }))
            sequence += 1
        }
        val observations = harness.recorder.liveBpmObservations()
        assertThat(observations.any { observation ->
            observation.status != LiveBpmAvailability.RELIABLE.name &&
                observation.reasonCode != null
        }).isTrue()
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)
        assertThat(harness.recorder.sampleCount).isEqualTo(1_000)
        assertThat(harness.coordinator.state.value.hrBpm).isNull()
    }

    @Test
    fun completeCaptureWrites15000RawSamplesAt500Hz() {
        val harness = Harness()
        startRecording(harness)
        val qrs = syntheticQrs(seconds = 30.0, bpm = 72.0)
        streamPrepared(
            harness,
            qrs,
            startSequence = harness.nextSequence,
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
        assertThat(parsed.schemaVersion).isEqualTo(3)
        assertThat(parsed.timingTrust.name).isEqualTo("SEQUENCE_RECONSTRUCTED")
        assertThat(parsed.samples[0].sensorTimestampMsRaw).isNotNull()
        assertThat(parsed.missingSampleCountKnown).isFalse()
        val duration = parsed.samples.last().relMs - parsed.samples.first().relMs
        assertThat(duration).isEqualTo(29_998L)
        val hz = 14_999.0 * 1000.0 / duration
        assertThat(hz).isWithin(5.0).of(500.0)
        assertThat(harness.sensor.startCount).isEqualTo(2)
    }

    @Test
    fun putDataItemSuccessIsQueuedNotSentToPhone() {
        val harness = Harness()
        startRecording(harness)
        streamUntilTerminal(harness, samples = FloatArray(15_000) { 0.12f })
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Success)
        assertThat(harness.coordinator.state.value.status).isEqualTo(EcgSyncSemantics.QUEUED)
        assertThat(harness.coordinator.state.value.status).isNotEqualTo(EcgSyncSemantics.ACKNOWLEDGED)
        assertThat(harness.coordinator.state.value.status).isNotEqualTo("Sent to phone")
        assertThat(harness.coordinator.state.value.error).isNull()
        assertThat(harness.pushedGzip).isNotNull()
    }

    @Test
    fun pushFailureStaysSavedOnWatchNotAcknowledged() {
        val harness = Harness(failPush = true)
        startRecording(harness)
        streamUntilTerminal(harness, samples = FloatArray(15_000) { 0.11f })
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Success)
        assertThat(harness.coordinator.state.value.status).isEqualTo(EcgSyncSemantics.SAVED_ON_WATCH)
        assertThat(harness.coordinator.state.value.status).isNotEqualTo(EcgSyncSemantics.ACKNOWLEDGED)
        assertThat(harness.coordinator.state.value.status).isNotEqualTo("Sent to phone")
        assertThat(harness.coordinator.state.value.error).isNotNull()
    }

    @Test
    fun hostStopDuringResolutionRequiredDoesNotCancelAndResumeRetriesConnect() {
        val harness = Harness()
        harness.sensor.availability = SensorAvailability(
            ready = false,
            reason = "Samsung Health Tracking Service is not installed.",
            issue = SensorIssue(
                SensorIssueCode.PACKAGE_NOT_INSTALLED,
                "Samsung Health Tracking Service is not installed.",
                SensorRecovery.RESOLVE_SERVICE,
            ),
        )
        harness.coordinator.startHardware()
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.ResolutionRequired)
        assertThat(harness.sensor.connectCount).isEqualTo(1)
        harness.coordinator.onHostStop()
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.ResolutionRequired)
        harness.coordinator.onHostResume()
        assertThat(harness.sensor.connectCount).isEqualTo(2)
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.ResolutionRequired)
    }

    @Test
    fun trackerPermissionErrorAfterConnectShowsPermissionRequired() {
        val harness = Harness()
        harness.coordinator.startHardware()
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.WaitingForContact)
        assertThat(harness.sensor.startCount).isEqualTo(1)
        harness.sensor.emitError(
            EcgSensorError(
                code = EcgSensorErrorCode.TRACKER,
                message = "Samsung ECG tracker error: PERMISSION_ERROR",
                issue = SensorIssue(
                    SensorIssueCode.PERMISSION_ERROR,
                    "Samsung ECG tracker error: PERMISSION_ERROR",
                    SensorRecovery.REQUEST_PERMISSION,
                ),
            ),
        )
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.PermissionRequired)
        harness.coordinator.onHostStop()
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.PermissionRequired)
    }

    @Test
    fun hostStopDuringPermissionRequiredDoesNotCancel() {
        val harness = Harness()
        harness.sensor.availability = SensorAvailability(
            ready = false,
            reason = "Body sensors permission is required to record ECG.",
            issue = SensorIssue(
                SensorIssueCode.PERMISSION_ERROR,
                "Body sensors permission is required to record ECG.",
                SensorRecovery.REQUEST_PERMISSION,
            ),
        )
        harness.coordinator.startHardware()
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.PermissionRequired)
        harness.coordinator.onHostStop()
        assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.PermissionRequired)
        harness.coordinator.onHostResume()
        assertThat(harness.sensor.connectCount).isEqualTo(1)
    }

    private class Harness(
        wrist: Wrist = Wrist.LEFT,
        compute: CoroutineDispatcher = Dispatchers.Unconfined,
        acquisition: CoroutineDispatcher = Dispatchers.Unconfined,
        main: CoroutineDispatcher = Dispatchers.Unconfined,
        failPush: Boolean = false,
    ) {
        val sensor = FakeSensor { now }
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
        private val delayWaiters = ArrayList<DelayWaiter>()
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
            pushToPhone = { _, gzip ->
                pushedGzip = gzip
                if (failPush) throw IllegalStateException("No connected phone")
            },
            watchInfo = { "watch" },
            offBodyFactory = { FakeOffBody() },
            mainDispatcher = main,
            computeDispatcher = compute,
            acquisitionDispatcher = acquisition,
            elapsedRealtime = { now },
            wallClock = { 1_700_000_000_000L + now },
            delayMs = { virtualDelay(it) },
            transitionLogger = { transitionLogs += it },
            bpmLogger = {},
            acquisitionLogger = { acquisitionLogs += it },
        )

        init {
            scope.launch(Dispatchers.Unconfined) {
                coordinator.state.collect { uiStates += it }
            }
        }

        fun advance(ms: Long) {
            now += ms
            val due = delayWaiters.filter { waiter -> now >= waiter.deadline }
            delayWaiters.removeAll(due.toSet())
            due.forEach { waiter -> waiter.deferred.complete(Unit) }
        }

        private suspend fun virtualDelay(ms: Long) {
            val deadline = now + ms
            if (now >= deadline) return
            val waiter = DelayWaiter(deadline, CompletableDeferred())
            delayWaiters += waiter
            waiter.deferred.await()
        }
    }

    private data class DelayWaiter(
        val deadline: Long,
        val deferred: CompletableDeferred<Unit>,
    )

    private class PauseableDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        @Volatile
        var paused = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val runNow = synchronized(queue) {
                if (paused) {
                    queue.addLast(block)
                    false
                } else {
                    true
                }
            }
            if (runNow) block.run()
        }

        fun pause() {
            paused = true
        }

        fun resume() {
            paused = false
            while (true) {
                val next = synchronized(queue) {
                    if (queue.isEmpty()) null else queue.removeFirst()
                } ?: break
                next.run()
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

    private class FakeSensor(
        private val nowMs: () -> Long,
    ) : EcgSensor {
        data class Listener(
            val generation: Long,
            val maxDurationMs: Long,
            val startedAtMs: Long,
            val onError: (EcgSensorError) -> Unit,
            val onBatch: (EcgBatch) -> Unit,
            val onDeadline: () -> Unit,
            var closedAtMs: Long? = null,
            var close: () -> Unit = {},
        ) {
            val setToCloseMs: Long? get() = closedAtMs?.minus(startedAtMs)
        }

        val listeners = arrayListOf<Listener>()
        var availability = SensorAvailability(ready = true)
        var connectCount = 0
        var startCount = 0
        var closeCount = 0
        var stopCount = 0
        var disconnectCount = 0
        val maxDurationMsHistory = ArrayList<Long>()
        val lastMaxDurationMs: Long get() = maxDurationMsHistory.lastOrNull() ?: -1L

        override fun connect(onResult: (SensorAvailability) -> Unit) {
            connectCount += 1
            onResult(availability)
        }

        override fun resolvePending(activity: Activity): Boolean = false

        override fun startEcg(
            maxDurationMs: Long,
            onError: (EcgSensorError) -> Unit,
            onBatch: (EcgBatch) -> Unit,
            onDeadline: () -> Unit,
        ): EcgSubscription {
            startCount += 1
            maxDurationMsHistory += maxDurationMs
            val listener = Listener(
                generation = startCount.toLong(),
                maxDurationMs = maxDurationMs,
                startedAtMs = nowMs(),
                onError = onError,
                onBatch = onBatch,
                onDeadline = onDeadline,
            )
            listeners += listener
            var closed = false
            val subscription = EcgSubscription {
                if (!closed) {
                    closed = true
                    closeCount += 1
                    listener.closedAtMs = nowMs()
                }
            }
            listener.close = { subscription.close() }
            return subscription
        }

        fun emit(batch: EcgBatch) {
            emit(listeners.lastIndex, batch)
        }

        fun emit(listenerIndex: Int, batch: EcgBatch) {
            listeners[listenerIndex].onBatch(batch)
        }

        fun emitError(error: EcgSensorError) {
            listeners.last().onError(error)
        }

        fun fireDeadline(listenerIndex: Int = listeners.lastIndex) {
            val listener = listeners[listenerIndex]
            listener.close()
            listener.onDeadline()
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
            assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.WaitingForContact)
            assertThat(harness.sensor.startCount).isEqualTo(1)
            assertThat(harness.recorder.isRecording).isFalse()
            harness.sensor.emit(batch(sequence = 0, leadOff = 0))
            assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.ArmedCountdown)
            assertThat(harness.sensor.startCount).isEqualTo(1)
            harness.advance(3_000L)
            assertThat(harness.coordinator.state.value.phase).isEqualTo(MeasurePhase.Recording)
            assertThat(harness.sensor.startCount).isEqualTo(2)
            assertThat(harness.sensor.lastMaxDurationMs).isEqualTo(30_000L)
            assertThat(harness.recorder.isRecording).isTrue()
            harness.nextSequence = 0
        }

        private fun streamUntilTerminal(harness: Harness, samples: FloatArray) {
            streamPrepared(
                harness,
                samples,
                startSequence = harness.nextSequence,
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
                        timestampStartMs = 1_000L + offset * 2L,
                        ppgGreen = ppgBatch,
                    ),
                )
                sequence += 1
                offset += count
            }
            harness.nextSequence = sequence
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

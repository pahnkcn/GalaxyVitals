package app.galaxyvitals.wear.ui

import android.os.SystemClock
import android.util.Log
import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.domain.Wrist
import app.galaxyvitals.wear.capture.EcgSessionRecorder
import app.galaxyvitals.wear.capture.LiveBpmObservation
import app.galaxyvitals.wear.sensors.EcgBatch
import app.galaxyvitals.wear.sensors.EcgSensor
import app.galaxyvitals.wear.sensors.EcgSensorError
import app.galaxyvitals.wear.sensors.EcgSubscription
import app.galaxyvitals.wear.sensors.OffBodyGate
import app.galaxyvitals.wear.sensors.PpgGreenBatch
import app.galaxyvitals.wear.sensors.SensorAvailability
import app.galaxyvitals.wear.sensors.SensorIssueCode
import app.galaxyvitals.wear.sensors.SensorRecovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One ECG_ON_DEMAND listener per capture; acquisition events reduce on a serial queue. */
class EcgMeasurementCoordinator(
    private val sensor: EcgSensor,
    private val recorder: EcgSessionRecorder,
    private val scope: CoroutineScope,
    private val persistenceScope: CoroutineScope,
    private val wrist: () -> Wrist,
    private val acquireForeground: () -> AutoCloseable,
    private val save: suspend (sessionId: String, gzip: ByteArray) -> Unit,
    private val pushToPhone: suspend (sessionId: String, gzip: ByteArray) -> Unit,
    private val watchInfo: () -> String,
    private val offBodyFactory: ((Boolean) -> Unit) -> OffBodyGate,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val acquisitionDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val delayMs: suspend (Long) -> Unit = { delay(it) },
    private val transitionLogger: (String) -> Unit = { Log.i(LOG_TAG, it) },
    private val bpmLogger: (String) -> Unit = { Log.i(BPM_LOG_TAG, it) },
    private val acquisitionLogger: (String) -> Unit = { Log.i(ACQ_LOG_TAG, it) },
) : AutoCloseable {
    private val _state = MutableStateFlow(MeasureUiState())
    val state: StateFlow<MeasureUiState> = _state.asStateFlow()

    private val events = Channel<AcqEvent>(Channel.UNLIMITED)
    private val reducerJob: Job = scope.launch(acquisitionDispatcher) {
        for (event in events) {
            try {
                reduce(event)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failTerminal("INTERNAL", "Recording failed", error.message ?: "Unexpected capture error.")
            }
        }
    }

    private var attemptId = 0L
    private var attemptStartedAt = 0L
    private var trackerReady = false
    private var terminal = false
    private var subscription: EcgSubscription? = null
    private var offBody: OffBodyGate? = null
    private var foregroundLease: AutoCloseable? = null
    private var connectTimeoutJob: Job? = null
    private var countdownJob: Job? = null
    private var streamMonitorJob: Job? = null
    private var bpmWorkerJob: Job? = null
    private var bpmTickerJob: Job? = null
    private var lastBatchAt = 0L
    private var sensorStopped = true
    private var sensorDisconnected = true
    private var offBodyStarted = false
    private var lastUiWaveformAt = 0L
    private var lastBpmScheduleAt = 0L
    private var lastLoggedPhase: MeasurePhase? = null
    private var lastLoggedCode: String? = null
    private var streamGeneration = 0L
    private var awaitingFirstBatch = false
    private var countdownDeadlineAt = 0L
    private var bpmDirty = false
    private var bpmInFlight = false
    private var captureStartedAt = 0L
    private var lastAcquisitionLogAt = 0L
    internal val liveEcgProcessor = LiveEcgProcessor()
    private val bpmSmoother = LiveBpmSmoother()
    @Volatile
    internal var bpmComputeCount = 0
        private set

    fun startHardware() {
        events.trySend(AcqEvent.Start)
    }

    fun retry() = startHardware()

    fun cancel() {
        events.trySend(AcqEvent.Cancel)
    }

    fun onHostStop() {
        events.trySend(AcqEvent.HostStop)
    }

    fun onHostResume() {
        events.trySend(AcqEvent.HostResume)
    }

    fun resolvePending(activity: android.app.Activity): Boolean = sensor.resolvePending(activity)

    override fun close() {
        val done = CountDownLatch(1)
        if (events.trySend(AcqEvent.Shutdown(done)).isSuccess) {
            if (!done.await(3, TimeUnit.SECONDS)) {
                subscription?.close()
            }
        } else {
            subscription?.close()
        }
    }

    private fun reduce(event: AcqEvent) {
        when (event) {
            AcqEvent.Start -> startNewAttempt()
            AcqEvent.Cancel -> onCancel()
            AcqEvent.HostStop -> onHostStopLocked()
            AcqEvent.HostResume -> onHostResumeLocked()
            is AcqEvent.ConnectResult -> {
                if (!isCurrent(event.attemptId) || terminal) return
                handleConnectResult(event.attemptId, event.availability)
            }
            is AcqEvent.ConnectTimeout -> {
                if (!isCurrent(event.attemptId) || terminal) return
                if (_state.value.phase == MeasurePhase.Connecting) {
                    unavailable("CONNECT_TIMEOUT", "Samsung ECG connection timed out.")
                }
            }
            is AcqEvent.Batch -> {
                if (!isCurrent(event.attemptId) || event.generation != streamGeneration || terminal) return
                handleBatch(event.batch)
            }
            is AcqEvent.Deadline -> {
                if (!isCurrent(event.attemptId) || event.generation != streamGeneration || terminal) return
                handleDeadline()
            }
            is AcqEvent.SensorError -> {
                if (!isCurrent(event.attemptId) || event.generation != streamGeneration || terminal) return
                handleSensorError(event.error)
            }
            is AcqEvent.OffBody -> {
                if (!isCurrent(event.attemptId) || terminal) return
                if (event.blocked && _state.value.phase in ACTIVE_BODY_PHASES) {
                    failTerminal("OFF_BODY", "Recording failed", "Watch not worn properly.")
                }
            }
            is AcqEvent.StreamStall -> {
                if (!isCurrent(event.attemptId) || event.generation != streamGeneration || terminal) return
                failTerminal("STREAM_STALLED", "Recording failed", "ECG sensor stopped sending data.")
            }
            is AcqEvent.BpmResult -> {
                if (event.generation == streamGeneration) bpmInFlight = false
                if (!isCurrent(event.attemptId) || event.generation != streamGeneration || terminal) return
                onBpmEstimate(event.snapshot, event.assessment)
            }
            is AcqEvent.BpmTick -> {
                if (!isCurrent(event.attemptId) || event.generation != streamGeneration || terminal) return
                maybeAdmitBpm(elapsedRealtime())
            }
            is AcqEvent.CountdownTick -> {
                if (!isCurrent(event.attemptId) || terminal) return
                handleCountdownTick()
            }
            is AcqEvent.DeadlineSettle -> {
                if (!isCurrent(event.attemptId) || event.generation != streamGeneration || terminal) return
                settleDeadline()
            }
            is AcqEvent.PersistResult -> handlePersistResult(event)
            is AcqEvent.Shutdown -> handleShutdown(event.done)
        }
    }

    private fun onCancel() {
        if (!terminal && _state.value.phase !in setOf(MeasurePhase.Success, MeasurePhase.Failed)) {
            failTerminal("CANCELLED", "Recording cancelled", "Start again when ready.")
        } else {
            cleanupAttempt(releaseLease = true)
        }
    }

    private fun onHostStopLocked() {
        val phase = _state.value.phase
        if (phase == MeasurePhase.PermissionRequired || phase == MeasurePhase.ResolutionRequired) {
            return
        }
        if (!terminal && phase !in setOf(MeasurePhase.Success, MeasurePhase.Failed)) {
            failTerminal("CANCELLED", "Recording cancelled", "Start again when ready.")
        } else {
            cleanupAttempt(releaseLease = true)
        }
    }

    private fun onHostResumeLocked() {
        if (_state.value.phase == MeasurePhase.ResolutionRequired) {
            startNewAttempt()
        }
    }

    private fun startNewAttempt() {
        cleanupAttempt(releaseLease = true)
        attemptId += 1
        val id = attemptId
        attemptStartedAt = elapsedRealtime()
        trackerReady = false
        terminal = false
        sensorStopped = false
        sensorDisconnected = false
        offBodyStarted = false
        lastBatchAt = 0L
        lastUiWaveformAt = 0L
        lastBpmScheduleAt = 0L
        lastLoggedPhase = null
        lastLoggedCode = null
        streamGeneration += 1
        awaitingFirstBatch = false
        countdownDeadlineAt = 0L
        bpmDirty = false
        bpmInFlight = false
        captureStartedAt = 0L
        lastAcquisitionLogAt = 0L
        bpmComputeCount = 0
        resetLive()
        transition(MeasurePhase.Connecting, "CONNECTING") {
            MeasureUiState(phase = MeasurePhase.Connecting, status = "Connecting sensor…")
        }
        connectTimeoutJob = scope.launch(mainDispatcher) {
            delayMs(CONNECT_TIMEOUT_MS)
            events.trySend(AcqEvent.ConnectTimeout(id))
        }
        try {
            sensor.connect { availability ->
                events.trySend(AcqEvent.ConnectResult(id, availability))
            }
        } catch (error: RuntimeException) {
            unavailable("CONNECT_FAILED", error.message ?: "Samsung ECG service could not start.")
        }
    }

    private fun handleConnectResult(id: Long, availability: SensorAvailability) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        if (!availability.ready) {
            val reason = availability.reason
                ?: availability.issue?.message
                ?: "Samsung ECG is not available for this package."
            when (availability.issue?.recovery) {
                SensorRecovery.REQUEST_PERMISSION -> showPermissionRequired(reason)
                SensorRecovery.RESOLVE_SERVICE -> {
                    transition(MeasurePhase.ResolutionRequired, "RESOLUTION_REQUIRED") {
                        MeasureUiState(
                            phase = MeasurePhase.ResolutionRequired,
                            status = "Samsung Health setup needed",
                            error = reason,
                        )
                    }
                }
                else -> {
                    val code = when (availability.issue?.code) {
                        SensorIssueCode.SDK_POLICY_ERROR -> "SDK_POLICY"
                        SensorIssueCode.TRACKER_UNSUPPORTED -> "TRACKER_UNSUPPORTED"
                        SensorIssueCode.CONNECTION_FAILED -> "CONNECTION_FAILED"
                        else -> "TRACKER_UNAVAILABLE"
                    }
                    if (trackerReady) {
                        failTerminal(code, "Recording failed", reason)
                    } else {
                        unavailable(code, reason)
                    }
                }
            }
            return
        }
        trackerReady = true
        try {
            offBody = offBodyFactory { blocked ->
                events.trySend(AcqEvent.OffBody(id, blocked))
            }.also {
                it.start()
                offBodyStarted = true
            }
        } catch (error: RuntimeException) {
            failTerminal("START_FAILED", "Recording failed", error.message ?: "Off-body monitor could not start.")
            return
        }
        startArmedCountdown(id)
    }

    private fun startArmedCountdown(id: Long) {
        countdownDeadlineAt = elapsedRealtime() + COUNTDOWN_MS
        transition(MeasurePhase.ArmedCountdown, "ARMED_COUNTDOWN") {
            MeasureUiState(
                phase = MeasurePhase.ArmedCountdown,
                status = COUNTDOWN_STATUS,
                remainingSec = 3,
                samsungReady = true,
                error = null,
            )
        }
        countdownJob?.cancel()
        countdownJob = scope.launch(mainDispatcher) {
            while (true) {
                events.trySend(AcqEvent.CountdownTick(id))
                val left = countdownDeadlineAt - elapsedRealtime()
                if (left <= 0L) return@launch
                delayMs(minOf(left, COUNTDOWN_TICK_MS))
            }
        }
    }

    private fun handleCountdownTick() {
        if (_state.value.phase != MeasurePhase.ArmedCountdown) return
        val left = countdownDeadlineAt - elapsedRealtime()
        val sec = ((left + 999L) / 1000L).toInt().coerceAtLeast(0)
        if (_state.value.remainingSec != sec) {
            _state.value = _state.value.copy(remainingSec = sec)
        }
        if (left <= 0L) onCountdownFinished()
    }

    private fun onCountdownFinished() {
        if (terminal || _state.value.phase != MeasurePhase.ArmedCountdown) return
        if (offBody?.isBlocked() == true) {
            failTerminal("OFF_BODY", "Recording failed", "Watch not worn properly.")
            return
        }
        val sessionId = "${wallClock()}-$attemptId"
        val selectedWrist = wrist()
        try {
            foregroundLease = acquireForeground()
            recorder.begin(
                sessionId = sessionId,
                wrist = selectedWrist,
                signFactor = EcgWearContract.signFactorFor(selectedWrist),
                nowMs = wallClock(),
            )
            recorder.addBpmObservation(LiveBpmAvailability.COLLECTING.name)
            countdownJob?.cancel()
            countdownJob = null
            liveEcgProcessor.beginCaptureWindow(EcgWearContract.signFactorFor(selectedWrist))
            awaitingFirstBatch = true
            lastUiWaveformAt = 0L
            lastBpmScheduleAt = 0L
            lastAcquisitionLogAt = 0L
            bpmDirty = false
            bpmInFlight = false
            captureStartedAt = elapsedRealtime()
            bpmSmoother.reset()
            transition(MeasurePhase.Recording, "RECORDING") {
                MeasureUiState(
                    phase = MeasurePhase.Recording,
                    status = "Recording",
                    remainingSec = remainingSecFromSamples(),
                    sessionId = sessionId,
                    samsungReady = true,
                    waveform = liveEcgProcessor.waveformFrame(0L),
                    bpm = LiveBpmState(LiveBpmAvailability.COLLECTING),
                    error = null,
                )
            }
            startEcgListener(attemptId)
            lastBatchAt = elapsedRealtime()
            startStreamMonitor(attemptId)
            startBpmTicker(attemptId)
        } catch (error: RuntimeException) {
            failTerminal(
                "FOREGROUND_OR_RECORDER_START",
                "Recording failed",
                error.message ?: "Health foreground service could not start.",
            )
        }
    }

    private fun startEcgListener(id: Long) {
        streamGeneration += 1
        val gen = streamGeneration
        val createdSubscription = sensor.startEcg(
            maxDurationMs = LISTENER_MAX_MS,
            onError = { error -> events.trySend(AcqEvent.SensorError(id, gen, error)) },
            onBatch = { batch -> events.trySend(AcqEvent.Batch(id, gen, batch)) },
            onDeadline = { events.trySend(AcqEvent.Deadline(id, gen)) },
        )
        if (!isCurrent(id) || terminal || gen != streamGeneration) {
            createdSubscription.close()
            return
        }
        subscription = createdSubscription
    }

    private fun startStreamMonitor(id: Long) {
        val gen = streamGeneration
        streamMonitorJob?.cancel()
        streamMonitorJob = scope.launch(mainDispatcher) {
            while (isCurrent(id) && !terminal) {
                delay(STREAM_POLL_MS)
                val ticked = elapsedRealtime()
                if (lastBatchAt > 0L && ticked - lastBatchAt > EcgWearContract.ECG_STALL_MS) {
                    events.trySend(AcqEvent.StreamStall(id, gen))
                    return@launch
                }
            }
        }
    }

    private fun handleBatch(batch: EcgBatch) {
        if (terminal || _state.value.phase != MeasurePhase.Recording) return
        lastBatchAt = elapsedRealtime()
        logCaptureBatch(batch)
        if (batch.samplesMv.isEmpty() || batch.samplesMv.any { !it.isFinite() }) {
            failTerminal("INVALID_BATCH", "Recording failed", "Samsung returned an invalid ECG batch.")
            return
        }
        if (awaitingFirstBatch && !batch.contactValid) {
            failTerminal(
                "CONTACT_NOT_READY",
                "Recording failed",
                "ECG contact was not detected. Keep touching the button and try again.",
            )
            return
        }
        awaitingFirstBatch = false
        if (!batch.contactValid) {
            failTerminal("CONTACT_LOSS", "Recording failed", "ECG contact was lost.")
            return
        }
        if (offBody?.isBlocked() == true) {
            failTerminal("OFF_BODY", "Recording failed", "Watch not worn properly.")
            return
        }
        val remaining = EcgSessionRecorder.EXPECTED_SAMPLES - recorder.sampleCount
        if (remaining <= 0) return
        val toStore = if (batch.samplesMv.size > remaining) batch.keepPrefix(remaining) else batch
        try {
            recorder.addEcgAtomically(toStore)
        } catch (error: Exception) {
            failTerminal("CAPTURE_INVALID", "Recording failed", error.message ?: "Invalid ECG signal.")
            return
        }
        liveEcgProcessor.append(toStore)
        val now = lastBatchAt
        publishWaveformIfDue(now)
        bpmDirty = true
        maybeAdmitBpm(now)
        val leftSec = remainingSecFromSamples()
        if (_state.value.remainingSec != leftSec) {
            _state.value = _state.value.copy(remainingSec = leftSec)
        }
        if (recorder.sampleCount == EcgSessionRecorder.EXPECTED_SAMPLES) {
            closeListenerFirst()
            finishValidateEncodeSave()
        }
    }

    private fun handleDeadline() {
        if (terminal) return
        closeListenerFirst()
        events.trySend(AcqEvent.DeadlineSettle(attemptId, streamGeneration))
    }

    private fun settleDeadline() {
        if (terminal || _state.value.phase != MeasurePhase.Recording) return
        if (recorder.sampleCount == EcgSessionRecorder.EXPECTED_SAMPLES) {
            finishValidateEncodeSave()
        } else {
            failTerminal(
                "INCOMPLETE_CAPTURE",
                "Recording failed",
                "The ECG recording did not complete. Please try again.",
            )
        }
    }

    private fun finishValidateEncodeSave() {
        if (terminal || _state.value.phase != MeasurePhase.Recording) return
        val snapshot = try {
            val listenerMs = (elapsedRealtime() - captureStartedAt).coerceAtLeast(0L)
            recorder.takeSnapshot(listenerDurationMs = listenerMs).also { it.requireCompleteCapture() }
        } catch (error: Exception) {
            failTerminal(
                "INCOMPLETE_CAPTURE",
                "Recording failed",
                error.message ?: "The ECG recording is incomplete.",
            )
            return
        }
        val id = attemptId
        val sessionId = _state.value.sessionId ?: snapshot.sessionId
        terminal = true
        cancelJobs()
        cleanupAcquisition()
        transition(MeasurePhase.Saving, "SAVING") {
            _state.value.copy(phase = MeasurePhase.Saving, status = "Saving…", remainingSec = 0)
        }
        val info = watchInfo()
        persistenceScope.launch {
            try {
                val recorded = withContext(computeDispatcher) { recorder.finish(snapshot, info) }
                save(recorded.sessionId, recorded.gzip)
                val pushed = try {
                    pushToPhone(recorded.sessionId, recorded.gzip)
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                events.trySend(
                    AcqEvent.PersistResult(
                        attemptId = id,
                        success = true,
                        sessionId = sessionId,
                        pushed = pushed,
                        error = null,
                    ),
                )
            } catch (cancelled: CancellationException) {
                events.trySend(
                    AcqEvent.PersistResult(
                        attemptId = id,
                        success = false,
                        sessionId = sessionId,
                        pushed = false,
                        error = cancelled.message,
                    ),
                )
                throw cancelled
            } catch (error: Exception) {
                events.trySend(
                    AcqEvent.PersistResult(
                        attemptId = id,
                        success = false,
                        sessionId = sessionId,
                        pushed = false,
                        error = error.message ?: "Could not save this recording. Please try again.",
                    ),
                )
            }
        }
    }

    private fun handlePersistResult(event: AcqEvent.PersistResult) {
        if (!isCurrent(event.attemptId) || _state.value.phase != MeasurePhase.Saving) return
        releaseForegroundLease()
        if (event.success) {
            transition(MeasurePhase.Success, "SUCCESS") {
                MeasureUiState(
                    phase = MeasurePhase.Success,
                    status = if (event.pushed) "Sent to phone" else "Saved on watch",
                    sessionId = event.sessionId,
                    remainingSec = 0,
                    error = if (event.pushed) {
                        null
                    } else {
                        "Phone not linked. Keep GalaxyVitals open nearby, then Sync."
                    },
                )
            }
        } else {
            transition(MeasurePhase.Failed, "SAVE_FAILED") {
                MeasureUiState(
                    phase = MeasurePhase.Failed,
                    status = "Save failed",
                    error = event.error ?: "Could not save this recording. Please try again.",
                )
            }
        }
    }

    private fun handleShutdown(done: CountDownLatch) {
        try {
            closeListenerFirst()
            val phase = _state.value.phase
            if (!terminal && phase !in setOf(
                    MeasurePhase.Success,
                    MeasurePhase.Failed,
                    MeasurePhase.PermissionRequired,
                    MeasurePhase.ResolutionRequired,
                )
            ) {
                failTerminal("CANCELLED", "Recording cancelled", "Start again when ready.")
            } else {
                cleanupAttempt(releaseLease = true)
            }
            terminal = true
            attemptId += 1
            events.close()
        } finally {
            done.countDown()
        }
    }

    private fun handleSensorError(error: EcgSensorError) {
        if (terminal) return
        if (error.issue?.recovery == SensorRecovery.REQUEST_PERMISSION) {
            showPermissionRequired(error.issue.message)
            return
        }
        if (trackerReady) {
            failTerminal(error.code.name, "Recording failed", error.message)
        } else {
            unavailable(error.code.name, error.message)
        }
    }

    private fun showPermissionRequired(message: String) {
        cleanupAttempt(releaseLease = true)
        transition(MeasurePhase.PermissionRequired, "PERMISSION_REQUIRED") {
            MeasureUiState(
                phase = MeasurePhase.PermissionRequired,
                status = "Body sensors needed",
                error = message,
            )
        }
    }

    private fun unavailable(code: String, message: String) {
        if (terminal) return
        terminal = true
        cleanupAttempt(releaseLease = true)
        transition(MeasurePhase.Unavailable, code) {
            MeasureUiState(
                phase = MeasurePhase.Unavailable,
                status = "ECG sensor not available",
                error = message,
            )
        }
    }

    private fun failTerminal(code: String, status: String, message: String) {
        if (terminal) return
        terminal = true
        recorder.cancel()
        cleanupAttempt(releaseLease = true)
        transition(MeasurePhase.Failed, code) {
            MeasureUiState(phase = MeasurePhase.Failed, status = status, error = message)
        }
    }

    private fun cleanupAttempt(releaseLease: Boolean) {
        cancelJobs()
        cleanupAcquisition()
        recorder.cancel()
        if (releaseLease) releaseForegroundLease()
        resetLive()
    }

    private fun cancelJobs() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        countdownJob?.cancel()
        countdownJob = null
        streamMonitorJob?.cancel()
        streamMonitorJob = null
        bpmWorkerJob?.cancel()
        bpmWorkerJob = null
        bpmTickerJob?.cancel()
        bpmTickerJob = null
        bpmInFlight = false
        bpmDirty = false
    }

    private fun closeListenerFirst() {
        val active = subscription
        subscription = null
        active?.close()
    }

    private fun cleanupAcquisition() {
        closeListenerFirst()
        streamGeneration += 1
        if (!sensorStopped) {
            runCatching(sensor::stop)
            sensorStopped = true
        }
        if (!sensorDisconnected) {
            runCatching(sensor::disconnect)
            sensorDisconnected = true
        }
        if (offBodyStarted) {
            runCatching { offBody?.stop() }
            offBodyStarted = false
        }
        offBody = null
    }

    private fun releaseForegroundLease() {
        foregroundLease?.close()
        foregroundLease = null
    }

    private fun resetLive() {
        liveEcgProcessor.reset(EcgWearContract.signFactorFor(wrist()))
        bpmSmoother.reset()
    }

    private fun startBpmTicker(id: Long) {
        val gen = streamGeneration
        bpmTickerJob?.cancel()
        bpmTickerJob = scope.launch(mainDispatcher) {
            while (isCurrent(id) && !terminal) {
                delayMs(BPM_UI_INTERVAL_MS)
                events.trySend(AcqEvent.BpmTick(id, gen))
            }
        }
    }

    private fun maybeAdmitBpm(now: Long) {
        if (terminal || _state.value.phase != MeasurePhase.Recording) return
        if (!bpmDirty || bpmInFlight) return
        if (lastBpmScheduleAt != 0L && now - lastBpmScheduleAt < BPM_UI_INTERVAL_MS) return
        lastBpmScheduleAt = now
        bpmDirty = false
        bpmInFlight = true
        val snapshot = BpmSnapshot(
            rawWindow = liveEcgProcessor.analysisSamples,
            livePpg = liveEcgProcessor.livePpg,
            signFactor = liveEcgProcessor.signFactor,
            analysisSampleCount = liveEcgProcessor.analysisSampleCount,
            atSampleIndex = (liveEcgProcessor.nextEcgSampleIndex - 1L).coerceAtLeast(0L),
            captureElapsedMs = (now - captureStartedAt).coerceAtLeast(0L),
            now = now,
            epoch = BpmEpoch.CAPTURE,
        )
        val id = attemptId
        val gen = streamGeneration
        bpmWorkerJob = scope.launch(computeDispatcher) {
            bpmComputeCount++
            val assessment = LiveBpmEstimator.estimate(
                rawWindow = snapshot.rawWindow,
                livePpg = snapshot.livePpg,
                signFactor = snapshot.signFactor,
                nowMs = snapshot.now,
                epoch = snapshot.epoch,
            )
            events.trySend(AcqEvent.BpmResult(id, gen, snapshot, assessment))
        }
    }

    private fun onBpmEstimate(snapshot: BpmSnapshot, assessment: BpmAssessment) {
        if (_state.value.phase != MeasurePhase.Recording) return
        val estimated = assessment.estimate
        if (estimated != null) {
            bpmLogger(
                "publish epoch=${estimated.epoch} source=${estimated.source} bSqi=${estimated.bSqi} " +
                    "rr=${estimated.rrCount} bpm=${estimated.bpm}",
            )
        } else {
            bpmLogger(
                "abstain epoch=${snapshot.epoch} analysisSamples=${snapshot.analysisSampleCount} " +
                    "ppgPoints=${snapshot.livePpg.size} reason=${assessment.reason}",
            )
        }
        val state = bpmSmoother.publish(snapshot.now, estimated)
        _state.value = _state.value.copy(bpm = state)
        recorder.addBpmObservation(
            LiveBpmObservation(
                atSampleIndex = snapshot.atSampleIndex,
                observedCaptureElapsedMs = snapshot.captureElapsedMs,
                status = state.availability.name,
                displayedBpm = state.estimate?.bpm,
                rawBpm = assessment.rawBpm,
                source = (state.estimate ?: estimated)?.source?.name,
                bSqi = assessment.bSqi,
                rrCount = assessment.rrCount,
                estimateAgeMs = state.estimateAgeMs,
                reasonCode = state.reason ?: assessment.reason?.name,
            ),
        )
    }

    private fun publishWaveformIfDue(now: Long) {
        if (lastUiWaveformAt != 0L && now - lastUiWaveformAt < UI_WAVEFORM_INTERVAL_MS) return
        val deltaMs = if (lastUiWaveformAt == 0L) UI_WAVEFORM_INTERVAL_MS else now - lastUiWaveformAt
        lastUiWaveformAt = now
        _state.value = _state.value.copy(waveform = liveEcgProcessor.waveformFrame(deltaMs))
    }

    private fun logCaptureBatch(batch: EcgBatch) {
        val phase = _state.value.phase
        val now = lastBatchAt
        val force = awaitingFirstBatch || batch.leadOff != 0
        if (!force && lastAcquisitionLogAt != 0L && now - lastAcquisitionLogAt < 1_000L) return
        lastAcquisitionLogAt = now
        acquisitionLogger(
            "phase=${phase.name} leadOff=${batch.leadOff} sequence=${batch.sequence} " +
                "batchSize=${batch.samplesMv.size} generation=$streamGeneration " +
                "samples=${recorder.sampleCount}",
        )
    }

    private fun remainingSecFromSamples(): Int {
        val left = (EcgSessionRecorder.EXPECTED_SAMPLES - recorder.sampleCount).coerceAtLeast(0)
        return ((left * EcgSessionRecorder.EXPECTED_PERIOD_MS + 999L) / 1000L).toInt()
    }

    private fun isCurrent(id: Long): Boolean = id == attemptId

    private inline fun transition(
        phase: MeasurePhase,
        code: String,
        state: () -> MeasureUiState,
    ) {
        _state.value = state()
        if (phase != lastLoggedPhase || code != lastLoggedCode) {
            lastLoggedPhase = phase
            lastLoggedCode = code
            transitionLogger(
                "attempt=$attemptId phase=${phase.name} code=$code samples=${recorder.sampleCount} " +
                    "elapsedMs=${(elapsedRealtime() - attemptStartedAt).coerceAtLeast(0L)}",
            )
        }
    }

    private data class BpmSnapshot(
        val rawWindow: FloatArray,
        val livePpg: List<LivePpgPoint>,
        val signFactor: Int,
        val analysisSampleCount: Int,
        val atSampleIndex: Long,
        val captureElapsedMs: Long,
        val now: Long,
        val epoch: BpmEpoch,
    )

    private sealed interface AcqEvent {
        data object Start : AcqEvent
        data object Cancel : AcqEvent
        data object HostStop : AcqEvent
        data object HostResume : AcqEvent
        data class ConnectResult(val attemptId: Long, val availability: SensorAvailability) : AcqEvent
        data class ConnectTimeout(val attemptId: Long) : AcqEvent
        data class Batch(val attemptId: Long, val generation: Long, val batch: EcgBatch) : AcqEvent
        data class Deadline(val attemptId: Long, val generation: Long) : AcqEvent
        data class SensorError(val attemptId: Long, val generation: Long, val error: EcgSensorError) : AcqEvent
        data class OffBody(val attemptId: Long, val blocked: Boolean) : AcqEvent
        data class StreamStall(val attemptId: Long, val generation: Long) : AcqEvent
        data class BpmResult(
            val attemptId: Long,
            val generation: Long,
            val snapshot: BpmSnapshot,
            val assessment: BpmAssessment,
        ) : AcqEvent
        data class BpmTick(val attemptId: Long, val generation: Long) : AcqEvent
        data class CountdownTick(val attemptId: Long) : AcqEvent
        data class DeadlineSettle(val attemptId: Long, val generation: Long) : AcqEvent
        data class PersistResult(
            val attemptId: Long,
            val success: Boolean,
            val sessionId: String?,
            val pushed: Boolean,
            val error: String?,
        ) : AcqEvent
        data class Shutdown(val done: CountDownLatch) : AcqEvent
    }

    companion object {
        private const val LOG_TAG = "EcgMeasurement"
        private const val BPM_LOG_TAG = "EcgBpm"
        private const val ACQ_LOG_TAG = "EcgAcquisition"
        private const val CONNECT_TIMEOUT_MS = 3_500L
        private const val COUNTDOWN_MS = 3_000L
        private const val COUNTDOWN_TICK_MS = 200L
        private const val LISTENER_MAX_MS = 30_000L
        private const val STREAM_POLL_MS = 200L
        private const val UI_WAVEFORM_INTERVAL_MS = 100L
        private const val BPM_UI_INTERVAL_MS = 1_000L
        private const val COUNTDOWN_STATUS = "ใส่นาฬิกาให้กระชับและวางนิ้วบนปุ่ม"
        private val ACTIVE_BODY_PHASES = setOf(MeasurePhase.ArmedCountdown, MeasurePhase.Recording)
    }
}

private fun EcgBatch.keepPrefix(count: Int): EcgBatch {
    if (count >= samplesMv.size) return this
    val ppg = ppgGreen?.let { green ->
        val kept = green.ecgSampleOffsets.indices.filter { index -> green.ecgSampleOffsets[index] < count }
        if (kept.isEmpty()) {
            null
        } else {
            PpgGreenBatch(
                values = IntArray(kept.size) { green.values[kept[it]] },
                ecgSampleOffsets = IntArray(kept.size) { green.ecgSampleOffsets[kept[it]] },
                sensorTimestampsMs = LongArray(kept.size) { green.sensorTimestampsMs[kept[it]] },
                nominalSampleRateHz = green.nominalSampleRateHz,
            )
        }
    }
    return copy(
        samplesMv = samplesMv.copyOf(count),
        sensorTimestampsMs = sensorTimestampsMs.copyOf(count),
        sampleFlags = sampleFlags.copyOf(count),
        ppgGreen = ppg,
    )
}

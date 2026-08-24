package app.galaxyvitals.wear.ui

import android.os.SystemClock
import android.util.Log
import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.domain.Wrist
import app.galaxyvitals.wear.capture.EcgSessionRecorder
import app.galaxyvitals.wear.sensors.EcgBatch
import app.galaxyvitals.wear.sensors.EcgSensor
import app.galaxyvitals.wear.sensors.EcgSensorError
import app.galaxyvitals.wear.sensors.EcgSubscription
import app.galaxyvitals.wear.sensors.OffBodyGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns exactly one Samsung measurement attempt and serializes every event on the UI dispatcher. */
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
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val transitionLogger: (String) -> Unit = { Log.i(LOG_TAG, it) },
    private val bpmLogger: (String) -> Unit = { Log.i(BPM_LOG_TAG, it) },
) : AutoCloseable {
    private val _state = MutableStateFlow(MeasureUiState())
    val state: StateFlow<MeasureUiState> = _state.asStateFlow()

    private var attemptId = 0L
    private var attemptStartedAt = 0L
    private var trackerReady = false
    private var terminal = false
    private var subscription: EcgSubscription? = null
    private var offBody: OffBodyGate? = null
    private var foregroundLease: AutoCloseable? = null
    private var connectTimeoutJob: Job? = null
    private var streamMonitorJob: Job? = null
    private var recordingTimerJob: Job? = null
    private var bpmWorkerJob: Job? = null
    private var lastBatchAt = 0L
    private var contactSince = 0L
    private var recordingStartedAt = 0L
    private var sensorStopped = true
    private var sensorDisconnected = true
    private var offBodyStarted = false
    private var lastUiWaveformAt = 0L
    private var lastBpmScheduleAt = 0L
    private var lastLoggedPhase: MeasurePhase? = null
    private var lastLoggedCode: String? = null
    private var streamGeneration = 0L
    private var sensorRun = SensorRun.PREFLIGHT
    private var settledWindowStarted = false
    private var preflightBpmSeed: BpmEstimate? = null
    private var captureBpmConfirmed = false
    private var preflightAbsoluteDeadline = 0L
    private var contactWaitDeadline = 0L
    private var bpmAfterContactDeadline = 0L
    private var captureBpmDeadline = 0L
    private val bpmLock = Any()
    private var pendingBpmSnapshot: BpmSnapshot? = null
    internal val liveEcgProcessor = LiveEcgProcessor()
    private val bpmSmoother = LiveBpmSmoother()

    fun startHardware() {
        scope.launch(mainDispatcher) { startNewAttempt() }
    }

    fun retry() = startHardware()

    fun cancel() {
        scope.launch(mainDispatcher) {
            if (!terminal && _state.value.phase !in setOf(MeasurePhase.Success, MeasurePhase.Failed)) {
                failTerminal("CANCELLED", "Recording cancelled", "Start again when ready.")
            } else {
                cleanupAttempt(releaseLease = true)
            }
        }
    }

    override fun close() {
        attemptId += 1
        terminal = true
        cleanupAttempt(releaseLease = true)
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
        contactSince = 0L
        recordingStartedAt = 0L
        lastUiWaveformAt = 0L
        lastBpmScheduleAt = 0L
        lastLoggedPhase = null
        lastLoggedCode = null
        streamGeneration += 1
        sensorRun = SensorRun.PREFLIGHT
        settledWindowStarted = false
        preflightBpmSeed = null
        captureBpmConfirmed = false
        preflightAbsoluteDeadline = 0L
        contactWaitDeadline = 0L
        bpmAfterContactDeadline = 0L
        captureBpmDeadline = 0L
        synchronized(bpmLock) { pendingBpmSnapshot = null }
        resetLive()
        transition(MeasurePhase.Connecting, "CONNECTING") {
            MeasureUiState(phase = MeasurePhase.Connecting, status = "Connecting sensor…")
        }
        connectTimeoutJob = scope.launch(mainDispatcher) {
            delay(CONNECT_TIMEOUT_MS)
            if (isCurrent(id) && _state.value.phase == MeasurePhase.Connecting) {
                unavailable("CONNECT_TIMEOUT", "Samsung ECG connection timed out.")
            }
        }
        try {
            sensor.connect { availability ->
                dispatchAttempt(id) {
                    connectTimeoutJob?.cancel()
                    connectTimeoutJob = null
                    if (!availability.ready) {
                        val code = if (availability.policyDenied) "SDK_POLICY" else "TRACKER_UNAVAILABLE"
                        val reason = availability.reason ?: "Samsung ECG is not available for this package."
                        if (trackerReady) {
                            failTerminal(code, "Recording failed", reason)
                        } else {
                            unavailable(code, reason)
                        }
                    } else {
                        trackerReady = true
                        startPreflight(id)
                    }
                }
            }
        } catch (error: RuntimeException) {
            unavailable("CONNECT_FAILED", error.message ?: "Samsung ECG service could not start.")
        }
    }

    private fun startPreflight(id: Long) {
        val now = elapsedRealtime()
        sensorRun = SensorRun.PREFLIGHT
        preflightAbsoluteDeadline = now + PREFLIGHT_ABSOLUTE_DEADLINE_MS
        contactWaitDeadline = now + CONTACT_WAIT_MS
        transition(MeasurePhase.Warmup, "PREFLIGHT") {
            _state.value.copy(
                phase = MeasurePhase.Warmup,
                status = "Checking ECG contact…",
                samsungReady = true,
                error = null,
            )
        }
        try {
            offBody = offBodyFactory { blocked ->
                dispatchAttempt(id) {
                    if (blocked && _state.value.phase in CAPTURE_PHASES) {
                        failTerminal("OFF_BODY", "Recording failed", "Watch not worn properly.")
                    }
                }
            }.also {
                it.start()
                offBodyStarted = true
            }
            startEcgListener(id)
        } catch (error: RuntimeException) {
            failTerminal("START_FAILED", "Recording failed", error.message ?: "ECG listener could not start.")
            return
        }
        lastBatchAt = elapsedRealtime()
        streamMonitorJob = scope.launch(mainDispatcher) {
            while (isCurrent(id) && !terminal) {
                delay(STREAM_POLL_MS)
                val ticked = elapsedRealtime()
                if (lastBatchAt > 0L && ticked - lastBatchAt > EcgWearContract.ECG_STALL_MS) {
                    failTerminal("STREAM_STALLED", "Recording failed", "ECG sensor stopped sending data.")
                    return@launch
                }
                checkDeadlines(ticked)
            }
        }
    }

    private fun startEcgListener(id: Long) {
        streamGeneration += 1
        val gen = streamGeneration
        val createdSubscription = sensor.startEcg(
            onError = { error -> dispatch(id, gen) { handleSensorError(error) } },
            onBatch = { batch -> dispatch(id, gen) { handleBatch(batch) } },
        )
        if (!isCurrent(id) || terminal || gen != streamGeneration) {
            createdSubscription.close()
            return
        }
        subscription = createdSubscription
    }

    private fun handleBatch(batch: EcgBatch) {
        if (terminal || !trackerReady) return
        lastBatchAt = elapsedRealtime()
        if (batch.samplesMv.isEmpty() || batch.samplesMv.any { !it.isFinite() }) {
            failTerminal("INVALID_BATCH", "Recording failed", "Samsung returned an invalid ECG batch.")
            return
        }
        checkDeadlines(lastBatchAt)
        if (terminal) return
        when (_state.value.phase) {
            MeasurePhase.Warmup, MeasurePhase.Ready, MeasurePhase.LeadOff,
            MeasurePhase.CalculatingBpm,
            -> handlePreflightBatch(batch)
            MeasurePhase.StartingCapture, MeasurePhase.Recording -> handleCaptureBatch(batch)
            else -> Unit
        }
    }

    private fun handlePreflightBatch(batch: EcgBatch) {
        if (!batch.contactValid) {
            onPreflightContactLost("CONTACT_LOST_PREFLIGHT", "Touch the button")
            return
        }
        if (offBody?.isBlocked() == true) {
            onPreflightContactLost("OFF_BODY_PREFLIGHT", "Wear the watch snugly")
            return
        }
        val now = elapsedRealtime()
        if (contactSince == 0L) {
            contactSince = now
            bpmAfterContactDeadline = now + BPM_AFTER_CONTACT_MS
        }
        val heldMs = now - contactSince
        if (heldMs < CONTACT_DEBOUNCE_MS) {
            liveEcgProcessor.append(batch)
            if (_state.value.phase != MeasurePhase.Ready || _state.value.status != "Hold still") {
                transition(MeasurePhase.Ready, "CONTACT_DEBOUNCE") {
                    _state.value.copy(
                        phase = MeasurePhase.Ready,
                        status = "Hold still",
                        bpm = LiveBpmState(LiveBpmAvailability.COLLECTING),
                    )
                }
            }
            publishWaveformIfDue(now)
            return
        }
        if (!settledWindowStarted) {
            liveEcgProcessor.beginSettledWindow(EcgWearContract.signFactorFor(wrist()))
            settledWindowStarted = true
        }
        liveEcgProcessor.append(batch)
        if (heldMs < PRE_RECORD_HOLD_MS) {
            if (_state.value.status != "Stabilizing sensor…") {
                transition(MeasurePhase.Ready, "SENSOR_SETTLE") {
                    _state.value.copy(
                        phase = MeasurePhase.Ready,
                        status = "Stabilizing sensor…",
                        bpm = LiveBpmState(LiveBpmAvailability.COLLECTING),
                    )
                }
            }
            publishWaveformIfDue(now)
            return
        }
        if (_state.value.phase != MeasurePhase.CalculatingBpm) {
            transition(MeasurePhase.CalculatingBpm, "CALCULATING_BPM") {
                _state.value.copy(
                    phase = MeasurePhase.CalculatingBpm,
                    status = "Calculating heart rate…",
                    bpm = LiveBpmState(LiveBpmAvailability.COLLECTING),
                )
            }
        }
        publishWaveformIfDue(now)
        requestBpmAnalysis(now, BpmEpoch.PREFLIGHT)
    }

    private fun onPreflightContactLost(code: String, status: String) {
        contactSince = 0L
        settledWindowStarted = false
        bpmAfterContactDeadline = 0L
        contactWaitDeadline = elapsedRealtime() + CONTACT_WAIT_MS
        resetLive()
        if (_state.value.phase != MeasurePhase.LeadOff || _state.value.status != status) {
            transition(MeasurePhase.LeadOff, code) {
                _state.value.copy(
                    phase = MeasurePhase.LeadOff,
                    status = status,
                    waveform = LiveWaveformFrame(),
                    bpm = LiveBpmState(LiveBpmAvailability.COLLECTING),
                )
            }
        }
    }

    private fun handleCaptureBatch(batch: EcgBatch) {
        if (!batch.contactValid) {
            failTerminal("CONTACT_LOSS", "Recording failed", "ECG contact was lost.")
            return
        }
        if (offBody?.isBlocked() == true) {
            failTerminal("OFF_BODY", "Recording failed", "Watch not worn properly.")
            return
        }
        val now = elapsedRealtime()
        if (_state.value.phase == MeasurePhase.StartingCapture) {
            if (!beginRecording()) return
        }
        liveEcgProcessor.append(batch)
        try {
            recorder.addEcg(batch)
        } catch (error: Exception) {
            failTerminal("CAPTURE_INVALID", "Recording failed", error.message ?: "Invalid ECG signal.")
            return
        }
        publishWaveformIfDue(now)
        requestBpmAnalysis(now, BpmEpoch.CAPTURE)
        if (!captureBpmConfirmed && captureBpmDeadline > 0L && now >= captureBpmDeadline) {
            failTerminal(
                "CAPTURE_BPM_TIMEOUT",
                "Recording failed",
                "Signal unstable. Please try again.",
            )
            return
        }
        if (recorder.sampleCount == EcgSessionRecorder.EXPECTED_SAMPLES) completeRecording()
    }

    private fun beginRecording(): Boolean {
        if (_state.value.phase == MeasurePhase.Recording || terminal) return false
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
        } catch (error: RuntimeException) {
            failTerminal(
                "FOREGROUND_OR_RECORDER_START",
                "Recording failed",
                error.message ?: "Health foreground service could not start.",
            )
            return false
        }
        val now = elapsedRealtime()
        recordingStartedAt = now
        captureBpmDeadline = now + CAPTURE_BPM_DEADLINE_MS
        captureBpmConfirmed = false
        val seed = preflightBpmSeed
        val bpmState = if (seed != null) {
            bpmSmoother.seed(now, seed)
        } else {
            LiveBpmState(LiveBpmAvailability.COLLECTING)
        }
        transition(MeasurePhase.Recording, "RECORDING") {
            _state.value.copy(
                phase = MeasurePhase.Recording,
                status = "Recording",
                remainingSec = 30,
                sessionId = sessionId,
                waveform = liveEcgProcessor.waveformFrame(0L),
                bpm = bpmState,
                error = null,
            )
        }
        val id = attemptId
        recordingTimerJob = scope.launch(mainDispatcher) {
            while (isCurrent(id) && !terminal && _state.value.phase == MeasurePhase.Recording) {
                val elapsed = elapsedRealtime() - recordingStartedAt
                val left = ((EcgWearContract.MEASURE_DURATION_MS - elapsed + 999L) / 1000L)
                    .toInt().coerceAtLeast(0)
                val current = _state.value
                if (current.remainingSec != left) {
                    _state.value = current.copy(remainingSec = left)
                }
                if (elapsed > RECORDING_DEADLINE_MS) {
                    failTerminal(
                        "INCOMPLETE_CAPTURE",
                        "Recording failed",
                        "The ECG recording did not complete. Please try again.",
                    )
                    return@launch
                }
                delay(STREAM_POLL_MS)
            }
        }
        return true
    }

    private fun startCapture(seed: BpmEstimate) {
        if (terminal || _state.value.phase != MeasurePhase.CalculatingBpm) return
        preflightBpmSeed = seed
        val now = elapsedRealtime()
        transition(MeasurePhase.StartingCapture, "STARTING_CAPTURE") {
            _state.value.copy(
                phase = MeasurePhase.StartingCapture,
                status = "Starting capture…",
                bpm = bpmSmoother.seed(now, seed),
            )
        }
        subscription?.close()
        subscription = null
        sensorRun = SensorRun.CAPTURE
        lastBatchAt = elapsedRealtime()
        bpmWorkerJob?.cancel()
        bpmWorkerJob = null
        synchronized(bpmLock) { pendingBpmSnapshot = null }
        lastBpmScheduleAt = 0L
        liveEcgProcessor.beginCaptureWindow(EcgWearContract.signFactorFor(wrist()))
        try {
            startEcgListener(attemptId)
        } catch (error: RuntimeException) {
            failTerminal("START_FAILED", "Recording failed", error.message ?: "ECG listener could not start.")
        }
    }

    private fun completeRecording() {
        if (terminal || _state.value.phase != MeasurePhase.Recording) return
        val snapshot = try {
            recorder.takeSnapshot().also { it.requireCompleteCapture() }
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
        streamMonitorJob?.cancel()
        streamMonitorJob = null
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        bpmWorkerJob?.cancel()
        bpmWorkerJob = null
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
                withContext(mainDispatcher) {
                    if (!isCurrent(id)) return@withContext
                    releaseForegroundLease()
                    transition(MeasurePhase.Success, "SUCCESS") {
                        MeasureUiState(
                            phase = MeasurePhase.Success,
                            status = if (pushed) "Sent to phone" else "Saved on watch",
                            sessionId = sessionId,
                            remainingSec = 0,
                            error = if (pushed) null else
                                "Phone not linked. Keep GalaxyVitals open nearby, then Sync.",
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                releaseForegroundLease()
                throw cancelled
            } catch (error: Exception) {
                withContext(mainDispatcher) {
                    if (isCurrent(id)) {
                        releaseForegroundLease()
                        transition(MeasurePhase.Failed, "SAVE_FAILED") {
                            MeasureUiState(
                                phase = MeasurePhase.Failed,
                                status = "Save failed",
                                error = error.message ?: "Could not save this recording. Please try again.",
                            )
                        }
                    }
                }
            }
        }
    }

    private fun handleSensorError(error: EcgSensorError) {
        if (terminal) return
        if (trackerReady) {
            failTerminal(error.code.name, "Recording failed", error.message)
        } else {
            unavailable(error.code.name, error.message)
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
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        streamMonitorJob?.cancel()
        streamMonitorJob = null
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        bpmWorkerJob?.cancel()
        bpmWorkerJob = null
        synchronized(bpmLock) { pendingBpmSnapshot = null }
        cleanupAcquisition()
        recorder.cancel()
        if (releaseLease) releaseForegroundLease()
        resetLive()
    }

    private fun cleanupAcquisition() {
        subscription?.close()
        subscription = null
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

    private fun dispatchAttempt(id: Long, event: () -> Unit) {
        scope.launch(mainDispatcher) {
            if (isCurrent(id) && !terminal) event()
        }
    }

    private fun dispatch(id: Long, generation: Long, event: () -> Unit) {
        scope.launch(mainDispatcher) {
            if (isCurrent(id) && generation == streamGeneration && !terminal) event()
        }
    }

    private fun checkDeadlines(now: Long) {
        if (terminal) return
        if (sensorRun != SensorRun.PREFLIGHT) return
        val phase = _state.value.phase
        if (phase in setOf(
                MeasurePhase.StartingCapture,
                MeasurePhase.Recording,
                MeasurePhase.Saving,
                MeasurePhase.Success,
            )
        ) {
            return
        }
        if (preflightAbsoluteDeadline > 0L && now >= preflightAbsoluteDeadline) {
            failTerminal(
                "PREFLIGHT_DEADLINE",
                "Recording failed",
                "ECG contact was not detected in time. Adjust the watch and try again.",
            )
            return
        }
        if (contactSince == 0L && contactWaitDeadline > 0L && now >= contactWaitDeadline) {
            failTerminal(
                "CONTACT_TIMEOUT",
                "Recording failed",
                "ECG contact was not detected in time. Adjust the watch and try again.",
            )
            return
        }
        if (contactSince > 0L &&
            preflightBpmSeed == null &&
            bpmAfterContactDeadline > 0L &&
            now >= bpmAfterContactDeadline
        ) {
            failTerminal(
                "BPM_PREFLIGHT_TIMEOUT",
                "Recording failed",
                "Could not measure heart rate. Please try again.",
            )
        }
    }

    private fun resetLive() {
        liveEcgProcessor.reset(EcgWearContract.signFactorFor(wrist()))
        bpmSmoother.reset()
    }

    private fun requestBpmAnalysis(now: Long, epoch: BpmEpoch) {
        val snapshot = BpmSnapshot(
            rawWindow = liveEcgProcessor.analysisSamples,
            livePpg = liveEcgProcessor.livePpg,
            signFactor = liveEcgProcessor.signFactor,
            analysisSampleCount = liveEcgProcessor.analysisSampleCount,
            now = now,
            epoch = epoch,
        )
        synchronized(bpmLock) { pendingBpmSnapshot = snapshot }
        val workerActive = bpmWorkerJob?.isActive == true
        if (workerActive) return
        if (lastBpmScheduleAt != 0L && now - lastBpmScheduleAt < BPM_UI_INTERVAL_MS) return
        lastBpmScheduleAt = now
        val id = attemptId
        val gen = streamGeneration
        bpmWorkerJob = scope.launch(computeDispatcher) {
            while (true) {
                val snap = synchronized(bpmLock) {
                    val taken = pendingBpmSnapshot
                    pendingBpmSnapshot = null
                    taken
                } ?: break
                val estimated = LiveBpmEstimator.estimate(
                    rawWindow = snap.rawWindow,
                    livePpg = snap.livePpg,
                    signFactor = snap.signFactor,
                    nowMs = snap.now,
                    epoch = snap.epoch,
                )
                withContext(mainDispatcher) {
                    if (!isCurrent(id) || gen != streamGeneration || terminal) return@withContext
                    onBpmEstimate(snap, estimated)
                }
            }
        }
    }

    private fun onBpmEstimate(snapshot: BpmSnapshot, estimated: BpmEstimate?) {
        val phase = _state.value.phase
        if (estimated != null) {
            bpmLogger(
                "publish epoch=${estimated.epoch} source=${estimated.source} bSqi=${estimated.bSqi} " +
                    "rr=${estimated.rrCount} bpm=${estimated.bpm}",
            )
        } else if (phase == MeasurePhase.Recording || phase == MeasurePhase.CalculatingBpm) {
            bpmLogger(
                "abstain epoch=${snapshot.epoch} analysisSamples=${snapshot.analysisSampleCount} " +
                    "ppgPoints=${snapshot.livePpg.size}",
            )
        }
        if (phase == MeasurePhase.CalculatingBpm &&
            estimated != null &&
            estimated.epoch == BpmEpoch.PREFLIGHT &&
            estimated.rrCount >= 4
        ) {
            startCapture(estimated)
            return
        }
        if (phase != MeasurePhase.Recording) return
        if (snapshot.epoch != BpmEpoch.CAPTURE) return
        if (estimated != null) {
            if (!captureBpmConfirmed) {
                captureBpmConfirmed = true
                captureBpmDeadline = 0L
            }
            _state.value = _state.value.copy(bpm = bpmSmoother.publish(snapshot.now, estimated))
        } else if (captureBpmConfirmed) {
            _state.value = _state.value.copy(bpm = bpmSmoother.publish(snapshot.now, estimated))
        }
    }

    private fun publishWaveformIfDue(now: Long) {
        if (lastUiWaveformAt != 0L && now - lastUiWaveformAt < UI_WAVEFORM_INTERVAL_MS) return
        val deltaMs = if (lastUiWaveformAt == 0L) UI_WAVEFORM_INTERVAL_MS else now - lastUiWaveformAt
        lastUiWaveformAt = now
        _state.value = _state.value.copy(waveform = liveEcgProcessor.waveformFrame(deltaMs))
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
        val now: Long,
        val epoch: BpmEpoch,
    )

    companion object {
        private const val LOG_TAG = "EcgMeasurement"
        private const val BPM_LOG_TAG = "EcgBpm"
        private const val CONNECT_TIMEOUT_MS = 3_500L
        private const val CONTACT_WAIT_MS = 10_000L
        private const val BPM_AFTER_CONTACT_MS = 15_000L
        private const val PREFLIGHT_ABSOLUTE_DEADLINE_MS = 25_000L
        private const val CAPTURE_BPM_DEADLINE_MS = 12_000L
        private const val CONTACT_DEBOUNCE_MS = 1_000L
        private const val SENSOR_SETTLE_MS = 3_000L
        private const val PRE_RECORD_HOLD_MS = CONTACT_DEBOUNCE_MS + SENSOR_SETTLE_MS
        private const val STREAM_POLL_MS = 200L
        private const val RECORDING_DEADLINE_MS = 31_000L
        private const val UI_WAVEFORM_INTERVAL_MS = 100L
        private const val BPM_UI_INTERVAL_MS = 1_000L
        private val CAPTURE_PHASES = setOf(MeasurePhase.StartingCapture, MeasurePhase.Recording)
    }
}

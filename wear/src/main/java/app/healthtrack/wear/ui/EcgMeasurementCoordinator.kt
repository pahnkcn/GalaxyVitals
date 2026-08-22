package app.healthtrack.wear.ui

import android.os.SystemClock
import android.util.Log
import app.healthtrack.data.protocol.EcgWearContract
import app.healthtrack.domain.Wrist
import app.healthtrack.wear.capture.EcgSessionRecorder
import app.healthtrack.wear.sensors.EcgBatch
import app.healthtrack.wear.sensors.EcgSensor
import app.healthtrack.wear.sensors.EcgSensorError
import app.healthtrack.wear.sensors.EcgSubscription
import app.healthtrack.wear.sensors.OffBodyGate
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
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val transitionLogger: (String) -> Unit = { Log.i(LOG_TAG, it) },
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
    private var preflightTimeoutJob: Job? = null
    private var streamMonitorJob: Job? = null
    private var recordingTimerJob: Job? = null
    private var lastBatchAt = 0L
    private var contactSince = 0L
    private var recordingStartedAt = 0L
    private var sensorStopped = true
    private var sensorDisconnected = true
    private var offBodyStarted = false
    private var lastUiWaveformAt = 0L
    private val live = ArrayList<Float>(EcgWearContract.DEFAULT_SR_HZ * 3)

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
        live.clear()
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
                dispatch(id) {
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
                dispatch(id) {
                    if (blocked && _state.value.phase == MeasurePhase.Recording) {
                        failTerminal("OFF_BODY", "Recording failed", "Watch not worn properly.")
                    }
                }
            }.also {
                it.start()
                offBodyStarted = true
            }
            val createdSubscription = sensor.startEcg(
                onError = { error -> dispatch(id) { handleSensorError(error) } },
                onBatch = { batch -> dispatch(id) { handleBatch(batch) } },
            )
            if (!isCurrent(id) || terminal) {
                createdSubscription.close()
                return
            }
            subscription = createdSubscription
        } catch (error: RuntimeException) {
            failTerminal("START_FAILED", "Recording failed", error.message ?: "ECG listener could not start.")
            return
        }
        lastBatchAt = elapsedRealtime()
        preflightTimeoutJob = scope.launch(mainDispatcher) {
            delay(CONTACT_PREFLIGHT_TIMEOUT_MS)
            if (isCurrent(id) && !terminal && _state.value.phase != MeasurePhase.Recording) {
                failTerminal(
                    "CONTACT_TIMEOUT",
                    "Recording failed",
                    "ECG contact was not detected in time. Adjust the watch and try again.",
                )
            }
        }
        streamMonitorJob = scope.launch(mainDispatcher) {
            while (isCurrent(id) && !terminal) {
                delay(STREAM_POLL_MS)
                if (lastBatchAt > 0L && elapsedRealtime() - lastBatchAt > EcgWearContract.ECG_STALL_MS) {
                    failTerminal("STREAM_STALLED", "Recording failed", "ECG sensor stopped sending data.")
                    return@launch
                }
            }
        }
    }

    private fun handleBatch(batch: EcgBatch) {
        if (terminal || !trackerReady) return
        lastBatchAt = elapsedRealtime()
        if (batch.samplesMv.isEmpty() || batch.samplesMv.any { !it.isFinite() }) {
            failTerminal("INVALID_BATCH", "Recording failed", "Samsung returned an invalid ECG batch.")
            return
        }
        when (_state.value.phase) {
            MeasurePhase.Warmup, MeasurePhase.Ready, MeasurePhase.LeadOff -> handlePreflightBatch(batch)
            MeasurePhase.Recording -> recordBatch(batch)
            else -> Unit
        }
    }

    private fun handlePreflightBatch(batch: EcgBatch) {
        if (!batch.contactValid) {
            contactSince = 0L
            transition(MeasurePhase.LeadOff, "CONTACT_LOST_PREFLIGHT") {
                _state.value.copy(phase = MeasurePhase.LeadOff, status = "Touch the button")
            }
            return
        }
        if (offBody?.isBlocked() == true) {
            contactSince = 0L
            transition(MeasurePhase.LeadOff, "OFF_BODY_PREFLIGHT") {
                _state.value.copy(phase = MeasurePhase.LeadOff, status = "Wear the watch snugly")
            }
            return
        }
        val now = elapsedRealtime()
        if (contactSince == 0L) contactSince = now
        if (now - contactSince < CONTACT_DEBOUNCE_MS) {
            transition(MeasurePhase.Ready, "CONTACT_DEBOUNCE") {
                _state.value.copy(phase = MeasurePhase.Ready, status = "Hold still")
            }
            return
        }
        beginRecording()
        // The debounce-completing batch is intentionally not recorded. The existing
        // subscription remains active and capture begins with the next Samsung batch.
    }

    private fun beginRecording() {
        if (_state.value.phase == MeasurePhase.Recording || terminal) return
        preflightTimeoutJob?.cancel()
        preflightTimeoutJob = null
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
            return
        }
        live.clear()
        recordingStartedAt = elapsedRealtime()
        transition(MeasurePhase.Recording, "RECORDING") {
            _state.value.copy(
                phase = MeasurePhase.Recording,
                status = "Recording",
                remainingSec = 30,
                sessionId = sessionId,
                liveMv = emptyList(),
                error = null,
            )
        }
        val id = attemptId
        recordingTimerJob = scope.launch(mainDispatcher) {
            while (isCurrent(id) && !terminal && _state.value.phase == MeasurePhase.Recording) {
                val elapsed = elapsedRealtime() - recordingStartedAt
                val left = ((EcgWearContract.MEASURE_DURATION_MS - elapsed + 999L) / 1000L)
                    .toInt().coerceAtLeast(0)
                _state.value = _state.value.copy(remainingSec = left)
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
    }

    private fun recordBatch(batch: EcgBatch) {
        if (!batch.contactValid) {
            failTerminal("CONTACT_LOSS", "Recording failed", "ECG contact was lost.")
            return
        }
        try {
            recorder.addEcg(batch)
        } catch (error: Exception) {
            failTerminal("CAPTURE_INVALID", "Recording failed", error.message ?: "Invalid ECG signal.")
            return
        }
        val now = elapsedRealtime()
        batch.samplesMv.forEach(live::add)
        val keep = EcgWearContract.DEFAULT_SR_HZ * 3
        if (live.size > keep) live.subList(0, live.size - keep).clear()
        if (now - lastUiWaveformAt >= UI_WAVEFORM_INTERVAL_MS) {
            lastUiWaveformAt = now
            _state.value = _state.value.copy(liveMv = live.toList())
        }
        if (recorder.sampleCount == EcgSessionRecorder.EXPECTED_SAMPLES) completeRecording()
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
        cleanupAcquisition()
        transition(MeasurePhase.Saving, "SAVING") {
            _state.value.copy(phase = MeasurePhase.Saving, status = "Saving…", remainingSec = 0)
        }
        val info = watchInfo()
        persistenceScope.launch {
            try {
                val recorded = withContext(Dispatchers.Default) { recorder.finish(snapshot, info) }
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
                                "Phone not linked. Keep GalaxyBridge open nearby, then Sync.",
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
        preflightTimeoutJob?.cancel()
        preflightTimeoutJob = null
        streamMonitorJob?.cancel()
        streamMonitorJob = null
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        cleanupAcquisition()
        recorder.cancel()
        if (releaseLease) releaseForegroundLease()
        live.clear()
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

    private fun dispatch(id: Long, event: () -> Unit) {
        scope.launch(mainDispatcher) {
            if (isCurrent(id) && !terminal) event()
        }
    }

    private fun isCurrent(id: Long): Boolean = id == attemptId

    private inline fun transition(
        phase: MeasurePhase,
        code: String,
        state: () -> MeasureUiState,
    ) {
        _state.value = state()
        transitionLogger(
            "attempt=$attemptId phase=${phase.name} code=$code samples=${recorder.sampleCount} " +
                "elapsedMs=${(elapsedRealtime() - attemptStartedAt).coerceAtLeast(0L)}",
        )
    }

    companion object {
        private const val LOG_TAG = "EcgMeasurement"
        private const val CONNECT_TIMEOUT_MS = 3_500L
        private const val CONTACT_PREFLIGHT_TIMEOUT_MS = 10_000L
        private const val CONTACT_DEBOUNCE_MS = 500L
        private const val STREAM_POLL_MS = 200L
        private const val RECORDING_DEADLINE_MS = 31_000L
        private const val UI_WAVEFORM_INTERVAL_MS = 100L
    }
}

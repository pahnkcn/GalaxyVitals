package app.healthtrack.wear.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.healthtrack.data.protocol.EcgWearContract
import app.healthtrack.data.protocol.ParsedEcgFile
import app.healthtrack.domain.Wrist
import app.healthtrack.wear.WearApplication
import app.healthtrack.wear.capture.MeasureForegroundLeaseManager
import app.healthtrack.wear.sensors.EcgSensor
import app.healthtrack.wear.sensors.OffBodyMonitor
import app.healthtrack.wear.sensors.SensorAvailability
import app.healthtrack.wear.sensors.SensorKind
import app.healthtrack.wear.store.watchInfoJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val latest: ParsedEcgFile?,
    val count: Int,
    val phoneNote: String,
    val wrist: Wrist,
)

enum class MeasurePhase {
    Connecting,
    Unavailable,
    Warmup,
    Ready,
    LeadOff,
    Recording,
    Saving,
    Success,
    Failed,
}

data class MeasureUiState(
    val phase: MeasurePhase = MeasurePhase.Connecting,
    val status: String = "Connecting…",
    val hrBpm: Int? = null,
    val remainingSec: Int = 30,
    val liveMv: List<Float> = emptyList(),
    val error: String? = null,
    val sessionId: String? = null,
    val samsungReady: Boolean = false,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WearApplication
    private val _state = MutableStateFlow(HomeUiState(null, 0, "Checking phone…", app.container.prefs.wrist))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) { app.container.store.parseAll() }
            val phones = app.container.dataLayer.connectedPhoneNames()
            _state.value = HomeUiState(
                latest = parsed.firstOrNull(),
                count = parsed.size,
                phoneNote = if (phones.isEmpty()) {
                    "Phone not linked. Keep HealthTrack open nearby."
                } else {
                    "Phone: ${phones.joinToString()}"
                },
                wrist = app.container.prefs.wrist,
            )
        }
    }
}

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WearApplication
    private val _sessions = MutableStateFlow<List<ParsedEcgFile>>(emptyList())
    val sessions: StateFlow<List<ParsedEcgFile>> = _sessions.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _sessions.value = withContext(Dispatchers.IO) { app.container.store.parseAll() }
        }
    }
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WearApplication
    private val _wrist = MutableStateFlow(app.container.prefs.wrist)
    val wrist: StateFlow<Wrist> = _wrist.asStateFlow()
    private val _sensorNote = MutableStateFlow("Checking sensor…")
    val sensorNote: StateFlow<String> = _sensorNote.asStateFlow()

    fun probeSensor() {
        app.container.samsungSensor.connect { avail: SensorAvailability ->
            _sensorNote.value = if (avail.ready) {
                "Samsung ECG tracker ready"
            } else {
                avail.reason ?: "Samsung ECG is not available for this package."
            }
            app.container.samsungSensor.disconnect()
        }
    }

    fun setWrist(value: Wrist) {
        app.container.prefs.wrist = value
        _wrist.value = value
    }
}

class MeasureViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WearApplication
    private val _state = MutableStateFlow(MeasureUiState())
    val state: StateFlow<MeasureUiState> = _state.asStateFlow()

    private var sensor: EcgSensor? = null
    private var autoStartOnContact = true
    private var leadOff = true
    private var hrOk = false
    private var lastEcgAt = 0L
    private var lastGoodHrAt = 0L
    private var ecgStarted = false
    private var tickJob: Job? = null
    private var delayedStartJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var sessionGeneration = 0L
    private var foregroundLease: MeasureForegroundLeaseManager.Lease? = null
    private val live = ArrayList<Float>(1000)
    private val offBody = OffBodyMonitor(application) { blocked ->
        if (blocked && _state.value.phase == MeasurePhase.Recording) {
            abortToLeadOff("Watch not worn properly")
        }
    }

    fun startSamsung() {
        resetSession()
        val generation = sessionGeneration
        _state.value = MeasureUiState(phase = MeasurePhase.Connecting, status = "Connecting sensor…")
        connectTimeoutJob = viewModelScope.launch {
            delay(3_500)
            if (generation == sessionGeneration && _state.value.phase == MeasurePhase.Connecting) {
                failSamsung("No Samsung ECG tracker on this watch. Use Record demo.")
            }
        }
        try {
            app.container.samsungSensor.connect { avail ->
                if (generation != sessionGeneration) {
                    return@connect
                }
                connectTimeoutJob?.cancel()
                connectTimeoutJob = null
                if (!avail.ready) {
                    failSamsung(
                        avail.reason ?: "This package is not allowed to use ECG_ON_DEMAND.",
                    )
                    return@connect
                }
                if (_state.value.phase != MeasurePhase.Connecting) {
                    app.container.samsungSensor.disconnect()
                    return@connect
                }
                try {
                    bind(app.container.samsungSensor, autoStart = true, readyLabel = "Touch the button")
                } catch (error: RuntimeException) {
                    failSamsung(error.message ?: "Samsung ECG could not be started.")
                }
            }
        } catch (error: RuntimeException) {
            failSamsung(error.message ?: "Samsung ECG permission or service start failed.")
        }
    }

    fun cancelRecording() {
        resetSession()
        _state.value = MeasureUiState(
            phase = MeasurePhase.Unavailable,
            status = "Recording cancelled",
            error = "Start again when ready.",
            samsungReady = false,
        )
    }

    fun startDemo() {
        resetSession()
        bind(app.container.demoSensor, autoStart = false, readyLabel = "Recording demo")
        val generation = sessionGeneration
        delayedStartJob = viewModelScope.launch {
            delay(200)
            if (generation == sessionGeneration &&
                sensor?.kind == SensorKind.DEMO &&
                _state.value.phase in setOf(MeasurePhase.Warmup, MeasurePhase.Ready)
            ) {
                beginRecording()
            }
        }
    }

    private fun bind(target: EcgSensor, autoStart: Boolean, readyLabel: String) {
        sensor = target
        val generation = sessionGeneration
        autoStartOnContact = autoStart
        leadOff = target.kind != SensorKind.DEMO
        hrOk = false
        lastEcgAt = 0L
        lastGoodHrAt = 0L
        ecgStarted = false
        if (target.kind == SensorKind.SAMSUNG) offBody.start()
        _state.value = MeasureUiState(
            phase = MeasurePhase.Warmup,
            status = "Warming up heart rate…",
            samsungReady = target.kind == SensorKind.SAMSUNG,
        )
        target.startHr { bpm, status ->
            if (generation != sessionGeneration || sensor !== target) {
                return@startHr
            }
            hrOk = status == EcgWearContract.HR_STATUS_OK && bpm > 0
            val current = _state.value
            if (hrOk) lastGoodHrAt = SystemClock.elapsedRealtime()
            if (current.phase == MeasurePhase.Recording && hrOk) {
                app.container.recorder.addHr(System.currentTimeMillis(), bpm)
            }
            _state.value = current.copy(hrBpm = bpm.coerceAtLeast(0))
            if (hrOk && current.phase == MeasurePhase.Warmup) {
                startEcgIfNeeded(target)
                _state.value = _state.value.copy(
                    phase = if (leadOff) MeasurePhase.LeadOff else MeasurePhase.Ready,
                    status = if (leadOff) "Touch the button" else readyLabel,
                )
                if (!leadOff && autoStartOnContact) beginRecording()
            }
        }
        if (target.kind == SensorKind.DEMO) {
            startEcgIfNeeded(target)
        }
    }

    private fun startEcgIfNeeded(target: EcgSensor) {
        if (ecgStarted) return
        ecgStarted = true
        val generation = sessionGeneration
        target.startEcg { mv, off ->
            if (generation != sessionGeneration || sensor !== target) {
                return@startEcg
            }
            lastEcgAt = SystemClock.elapsedRealtime()
            leadOff = off
            val phase = _state.value.phase
            if (off) {
                if (phase == MeasurePhase.Recording) {
                    abortToLeadOff("Lost contact")
                } else if (hrOk) {
                    _state.value = _state.value.copy(
                        phase = MeasurePhase.LeadOff,
                        status = "Touch the button",
                    )
                }
                return@startEcg
            }
            if (mv.any { !it.isFinite() }) {
                if (phase == MeasurePhase.Recording) {
                    abortToLeadOff("Invalid ECG signal")
                }
                return@startEcg
            }
            if (phase == MeasurePhase.Recording) {
                app.container.recorder.addEcg(mv)
                synchronized(live) {
                    live.addAll(mv.toList())
                    val keep = 500 * 3
                    if (live.size > keep) {
                        repeat(live.size - keep) { live.removeAt(0) }
                    }
                    _state.value = _state.value.copy(liveMv = live.toList())
                }
            } else if (autoStartOnContact && hasRecentHeartRate()) {
                beginRecording()
            }
        }
    }

    private fun beginRecording() {
        if (_state.value.phase == MeasurePhase.Recording) return
        val activeSensor = sensor ?: return
        if (activeSensor.kind == SensorKind.SAMSUNG && offBody.isBlocked()) {
            _state.value = _state.value.copy(
                phase = MeasurePhase.LeadOff,
                status = "Watch not worn properly",
            )
            return
        }
        if (activeSensor.kind == SensorKind.SAMSUNG && !hasRecentHeartRate()) {
            hrOk = false
            _state.value = _state.value.copy(
                phase = MeasurePhase.Warmup,
                status = "Waiting for a clear heart-rate signal…",
            )
            return
        }
        val sessionId = System.currentTimeMillis().toString()
        val wrist = app.container.prefs.wrist
        app.container.recorder.begin(
            sessionId = sessionId,
            wrist = wrist,
            signFactor = EcgWearContract.signFactorFor(wrist),
        )
        if (activeSensor.kind == SensorKind.SAMSUNG) {
            try {
                foregroundLease = app.container.measureForegroundLeases.acquire()
            } catch (error: RuntimeException) {
                failSamsung(
                    error.message ?: "Health foreground service could not start. Check permissions.",
                )
                return
            }
        }
        delayedStartJob?.cancel()
        delayedStartJob = null
        synchronized(live) { live.clear() }
        _state.value = _state.value.copy(
            phase = MeasurePhase.Recording,
            status = "Recording",
            remainingSec = 30,
            sessionId = sessionId,
            error = null,
        )
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - started
                val left = ((EcgWearContract.MEASURE_DURATION_MS - elapsed) / 1000L).toInt().coerceAtLeast(0)
                val now = SystemClock.elapsedRealtime()
                val stalled = lastEcgAt > 0 && now - lastEcgAt > EcgWearContract.ECG_STALL_MS
                val hrLost = lastGoodHrAt > 0 && now - lastGoodHrAt > EcgWearContract.HR_LOST_ABORT_MS
                if (autoStartOnContact && stalled) {
                    abortToLeadOff("Lost contact")
                    return@launch
                }
                if (autoStartOnContact && hrLost) {
                    hrOk = false
                    abortToLeadOff("No clear heart-rate signal")
                    return@launch
                }
                _state.value = _state.value.copy(remainingSec = left)
                if (elapsed >= EcgWearContract.MEASURE_DURATION_MS) {
                    complete()
                    return@launch
                }
                delay(200)
            }
        }
    }

    private fun complete() {
        tickJob?.cancel()
        tickJob = null
        delayedStartJob?.cancel()
        delayedStartJob = null
        val snapshot = try {
            app.container.recorder.takeSnapshot()
        } catch (_: Exception) {
            resetSession()
            _state.value = MeasureUiState(
                phase = MeasurePhase.Failed,
                status = "Save failed",
                error = "Could not save this recording. Please try again.",
            )
            return
        }
        sessionGeneration += 1
        val completionGeneration = sessionGeneration
        val completedSensor = sensor
        sensor = null
        val persistenceForegroundLease = foregroundLease
        foregroundLease = null
        val watchInfo = watchInfoJson(getApplication())
        val lastHr = _state.value.hrBpm
        _state.value = _state.value.copy(
            phase = MeasurePhase.Saving,
            status = "Saving…",
            remainingSec = 0,
        )
        app.container.persistenceScope.launch {
            try {
                val recorded = withContext(Dispatchers.Default) {
                    app.container.recorder.finish(snapshot, watchInfo)
                }
                try {
                    app.container.store.save(recorded.sessionId, recorded.gzip)
                } finally {
                    persistenceForegroundLease?.close()
                }
                val pushed = try {
                    app.container.dataLayer.putSession(recorded.sessionId, recorded.gzip)
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                withContext(Dispatchers.Main.immediate) {
                    if (completionGeneration == sessionGeneration) {
                        _state.value = MeasureUiState(
                            phase = MeasurePhase.Success,
                            status = if (pushed) "Sent to phone" else "Saved on watch",
                            sessionId = recorded.sessionId,
                            hrBpm = lastHr,
                            remainingSec = 0,
                            error = if (pushed) {
                                null
                            } else {
                                "Phone not linked. Keep HealthTrack open nearby, then Sync."
                            },
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    if (completionGeneration == sessionGeneration) {
                        _state.value = MeasureUiState(
                            phase = MeasurePhase.Failed,
                            status = "Save failed",
                            error = "Could not save this recording. Please try again.",
                        )
                    }
                }
            } finally {
                persistenceForegroundLease?.close()
            }
        }
        try {
            completedSensor?.stop()
        } catch (_: Exception) {
            // The immutable snapshot is already queued for persistence.
        }
        try {
            completedSensor?.disconnect()
        } catch (_: Exception) {
            // The immutable snapshot is already queued for persistence.
        }
        try {
            offBody.stop()
        } catch (_: Exception) {
            // The immutable snapshot is already queued for persistence.
        }
    }

    private fun abortToLeadOff(message: String) {
        tickJob?.cancel()
        tickJob = null
        app.container.recorder.cancel()
        releaseForegroundLease()
        synchronized(live) { live.clear() }
        _state.value = _state.value.copy(
            phase = MeasurePhase.LeadOff,
            status = message,
            remainingSec = 30,
            liveMv = emptyList(),
        )
    }

    private fun resetSession() {
        sessionGeneration += 1
        tickJob?.cancel()
        tickJob = null
        delayedStartJob?.cancel()
        delayedStartJob = null
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        val activeSensor = sensor
        sensor = null
        activeSensor?.stop()
        activeSensor?.disconnect()
        if (activeSensor !== app.container.samsungSensor) {
            app.container.samsungSensor.disconnect()
        }
        app.container.recorder.cancel()
        releaseForegroundLease()
        offBody.stop()
        lastGoodHrAt = 0L
        lastEcgAt = 0L
        ecgStarted = false
        synchronized(live) { live.clear() }
    }

    private fun failSamsung(message: String) {
        resetSession()
        _state.value = MeasureUiState(
            phase = MeasurePhase.Unavailable,
            status = "ECG sensor not available",
            error = message,
            samsungReady = false,
        )
    }

    private fun releaseForegroundLease() {
        foregroundLease?.close()
        foregroundLease = null
    }

    private fun hasRecentHeartRate(now: Long = SystemClock.elapsedRealtime()): Boolean =
        isRecentHeartRate(hrOk, lastGoodHrAt, now)

    override fun onCleared() {
        resetSession()
        super.onCleared()
    }
}

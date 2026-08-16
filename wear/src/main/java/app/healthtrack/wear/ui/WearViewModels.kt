package app.healthtrack.wear.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.healthtrack.data.protocol.EcgWearContract
import app.healthtrack.data.protocol.ParsedEcgFile
import app.healthtrack.domain.Wrist
import app.healthtrack.wear.WearApplication
import app.healthtrack.wear.capture.MeasureForegroundService
import app.healthtrack.wear.sensors.EcgSensor
import app.healthtrack.wear.sensors.OffBodyMonitor
import app.healthtrack.wear.sensors.SensorAvailability
import app.healthtrack.wear.sensors.SensorKind
import app.healthtrack.wear.store.watchInfoJson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
            val parsed = app.container.store.parseAll()
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
        _sessions.value = app.container.store.parseAll()
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
    private var tickJob: Job? = null
    private val live = ArrayList<Float>(1000)
    private val offBody = OffBodyMonitor(application) { blocked ->
        if (blocked && _state.value.phase == MeasurePhase.Recording) {
            abortToLeadOff("Watch not worn properly")
        }
    }

    fun startSamsung() {
        resetSession()
        _state.value = MeasureUiState(phase = MeasurePhase.Connecting, status = "Connecting sensor…")
        app.container.samsungSensor.connect { avail ->
            if (!avail.ready) {
                _state.value = MeasureUiState(
                    phase = MeasurePhase.Unavailable,
                    status = "ECG sensor not available",
                    error = avail.reason ?: "This package is not allowed to use ECG_ON_DEMAND.",
                    samsungReady = false,
                )
                return@connect
            }
            bind(app.container.samsungSensor, autoStart = true, readyLabel = "Touch the button")
        }
    }

    fun startDemo() {
        resetSession()
        bind(app.container.demoSensor, autoStart = false, readyLabel = "Recording demo")
        viewModelScope.launch {
            delay(200)
            beginRecording()
        }
    }

    private fun bind(target: EcgSensor, autoStart: Boolean, readyLabel: String) {
        sensor = target
        autoStartOnContact = autoStart
        leadOff = target.kind != SensorKind.DEMO
        hrOk = false
        lastEcgAt = 0L
        offBody.start()
        _state.value = MeasureUiState(
            phase = MeasurePhase.Warmup,
            status = "Warming up heart rate…",
            samsungReady = target.kind == SensorKind.SAMSUNG,
        )
        target.startHr { bpm, status ->
            hrOk = status == EcgWearContract.HR_STATUS_OK && bpm > 0
            val current = _state.value
            if (current.phase == MeasurePhase.Recording && hrOk) {
                app.container.recorder.addHr(System.currentTimeMillis(), bpm)
            }
            _state.value = current.copy(hrBpm = bpm.coerceAtLeast(0))
            if (hrOk && current.phase == MeasurePhase.Warmup) {
                _state.value = _state.value.copy(
                    phase = if (leadOff) MeasurePhase.LeadOff else MeasurePhase.Ready,
                    status = if (leadOff) "Touch the button" else readyLabel,
                )
                if (!leadOff && autoStartOnContact) beginRecording()
            }
        }
        target.startEcg { mv, off ->
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
            } else if (hrOk && autoStartOnContact) {
                beginRecording()
            }
        }
    }

    private fun beginRecording() {
        if (_state.value.phase == MeasurePhase.Recording) return
        if (offBody.isBlocked()) {
            _state.value = _state.value.copy(
                phase = MeasurePhase.LeadOff,
                status = "Watch not worn properly",
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
        MeasureForegroundService.start(getApplication())
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
                val stalled = lastEcgAt > 0 &&
                    SystemClock.elapsedRealtime() - lastEcgAt > EcgWearContract.ECG_STALL_MS
                if (stalled && autoStartOnContact) {
                    abortToLeadOff("Lost contact")
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
        sensor?.stop()
        offBody.stop()
        MeasureForegroundService.stop(getApplication())
        viewModelScope.launch {
            try {
                val recorded = app.container.recorder.finish(watchInfoJson(getApplication()))
                app.container.store.save(recorded.sessionId, recorded.gzip)
                runCatching { app.container.dataLayer.putSession(recorded.sessionId, recorded.gzip) }
                _state.value = MeasureUiState(
                    phase = MeasurePhase.Success,
                    status = "Sent to phone",
                    sessionId = recorded.sessionId,
                    hrBpm = _state.value.hrBpm,
                    remainingSec = 0,
                )
            } catch (t: Throwable) {
                _state.value = MeasureUiState(
                    phase = MeasurePhase.Failed,
                    status = "Save failed",
                    error = t.message,
                )
            }
        }
    }

    private fun abortToLeadOff(message: String) {
        tickJob?.cancel()
        app.container.recorder.cancel()
        MeasureForegroundService.stop(getApplication())
        synchronized(live) { live.clear() }
        _state.value = _state.value.copy(
            phase = MeasurePhase.LeadOff,
            status = message,
            remainingSec = 30,
            liveMv = emptyList(),
        )
    }

    private fun resetSession() {
        tickJob?.cancel()
        sensor?.stop()
        sensor?.disconnect()
        sensor = null
        app.container.recorder.cancel()
        MeasureForegroundService.stop(getApplication())
        offBody.stop()
        synchronized(live) { live.clear() }
    }

    override fun onCleared() {
        resetSession()
        super.onCleared()
    }
}

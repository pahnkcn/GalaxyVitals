package app.galaxyvitals.wear.ui

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.galaxyvitals.data.protocol.ParsedEcgFile
import app.galaxyvitals.data.protocol.WaveformPoint
import app.galaxyvitals.data.protocol.WaveformScale
import app.galaxyvitals.domain.Wrist
import app.galaxyvitals.wear.WearApplication
import app.galaxyvitals.wear.sensors.OffBodyMonitor
import app.galaxyvitals.wear.sensors.SensorAvailability
import app.galaxyvitals.wear.store.watchInfoJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt
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
    PermissionRequired,
    ResolutionRequired,
    WaitingForContact,
    ArmedCountdown,
    Recording,
    Saving,
    Success,
    Failed,
}

data class LiveWaveformFrame(
    val points: List<WaveformPoint> = emptyList(),
    val firstSampleIndex: Long = -1_499L,
    val lastSampleIndex: Long = 0L,
    val scale: WaveformScale = WaveformScale.Default,
)

data class MeasureUiState(
    val phase: MeasurePhase = MeasurePhase.Connecting,
    val status: String = "Connecting…",
    val remainingSec: Int = 30,
    val error: String? = null,
    val sessionId: String? = null,
    val samsungReady: Boolean = false,
    val bpm: LiveBpmState = LiveBpmState(LiveBpmAvailability.COLLECTING),
    val waveform: LiveWaveformFrame = LiveWaveformFrame(),
) {
    val liveMv: List<Float>
        get() = waveform.points.map { it.valueMv }

    val hrBpm: Int?
        get() = if (bpm.availability == LiveBpmAvailability.RELIABLE) {
            bpm.estimate?.bpm?.roundToInt()
        } else {
            null
        }
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WearApplication
    private val _state = MutableStateFlow(HomeUiState(null, 0, "Checking phone…", app.container.prefs.wrist))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            app.container.storeChanges.collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                app.container.store.parseAll().map(WatchSessionBpm::withDisplayBpm)
            }
            val phones = app.container.dataLayer.connectedPhoneNames()
            _state.value = HomeUiState(
                latest = parsed.firstOrNull(),
                count = parsed.size,
                phoneNote = if (phones.isEmpty()) {
                    "Phone not linked. Keep GalaxyVitals open nearby."
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

    init {
        viewModelScope.launch {
            app.container.storeChanges.collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _sessions.value = withContext(Dispatchers.IO) {
                app.container.store.parseAll().map(WatchSessionBpm::withDisplayBpm)
            }
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
        app.container.ecgSensor.connect { avail: SensorAvailability ->
            _sensorNote.value = if (avail.ready) {
                avail.reason ?: "Samsung ECG tracker ready"
            } else {
                avail.reason ?: "Samsung ECG is not available for this package."
            }
            app.container.ecgSensor.disconnect()
        }
    }

    fun setWrist(value: Wrist) {
        app.container.prefs.wrist = value
        _wrist.value = value
    }
}

class MeasureViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WearApplication
    private val coordinator = EcgMeasurementCoordinator(
        sensor = app.container.ecgSensor,
        recorder = app.container.recorder,
        scope = viewModelScope,
        persistenceScope = app.container.persistenceScope,
        wrist = { app.container.prefs.wrist },
        acquireForeground = { app.container.measureForegroundLeases.acquire() },
        save = { sessionId, gzip -> app.container.store.save(sessionId, gzip) },
        pushToPhone = { sessionId, gzip -> app.container.dataLayer.putSession(sessionId, gzip) },
        watchInfo = { watchInfoJson(application) },
        offBodyFactory = { onChange -> OffBodyMonitor(application, onChange) },
    )

    val state: StateFlow<MeasureUiState> = coordinator.state

    fun startSamsung() = coordinator.startHardware()

    fun retry() = coordinator.retry()

    fun cancelRecording() = coordinator.cancel()

    fun onHostStop() = coordinator.onHostStop()

    fun onHostResume() = coordinator.onHostResume()

    fun resolveSamsung(activity: Activity) {
        coordinator.resolvePending(activity)
    }

    override fun onCleared() {
        coordinator.close()
        super.onCleared()
    }
}

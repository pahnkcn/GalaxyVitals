package app.healthtrack.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.healthtrack.HealthTrackApp
import app.healthtrack.data.wear.WearLinkStatus
import app.healthtrack.domain.EcgSample
import app.healthtrack.domain.EcgSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val latest: EcgSession? = null,
    val count: Int = 0,
    val wear: WearLinkStatus = WearLinkStatus(false, emptyList(), "Checking watch link…"),
    val message: String? = null,
    val busy: Boolean = false,
)

class HealthTrackViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HealthTrackApp
    private val repo = app.container.ecgRepository
    private val wear = app.container.wearSyncClient

    val sessions: StateFlow<List<EcgSession>> = repo.observeSessions()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _samples = MutableStateFlow<List<EcgSample>>(emptyList())
    val samples: StateFlow<List<EcgSample>> = _samples.asStateFlow()

    init {
        // Drive Home from the same list History uses. Separate LIMIT 1 / COUNT
        // flows were not emitting an initial row after process death, so Home
        // stayed empty while History already showed imported / Wear sessions.
        viewModelScope.launch {
            sessions.collect { list ->
                _home.value = _home.value.copy(
                    latest = list.firstOrNull(),
                    count = list.size,
                )
            }
        }
        refreshWear()
    }

    fun refreshWear() {
        viewModelScope.launch {
            _home.value = _home.value.copy(wear = wear.status())
        }
    }

    fun importUri(uri: Uri) {
        viewModelScope.launch {
            _home.value = _home.value.copy(busy = true, message = null)
            try {
                val session = repo.importUri(uri)
                _home.value = _home.value.copy(
                    busy = false,
                    message = "Imported ${session.sessionId}",
                )
            } catch (t: Throwable) {
                _home.value = _home.value.copy(
                    busy = false,
                    message = t.message ?: "Import failed",
                )
            }
        }
    }

    fun loadDemo() {
        viewModelScope.launch {
            _home.value = _home.value.copy(busy = true, message = null)
            try {
                repo.importDemo()
                _home.value = _home.value.copy(busy = false, message = "Loaded a demo 500 Hz trace")
            } catch (t: Throwable) {
                _home.value = _home.value.copy(busy = false, message = t.message)
            }
        }
    }

    fun requestSync() {
        viewModelScope.launch {
            _home.value = _home.value.copy(busy = true, message = null)
            try {
                val n = wear.requestSyncNow()
                _home.value = _home.value.copy(
                    busy = false,
                    message = if (n == 0) {
                        "No HealthTrack watch node. Install the watch app, or import a csv.gz."
                    } else {
                        "Asked $n watch node(s) to sync."
                    },
                    wear = wear.status(),
                )
            } catch (t: Throwable) {
                _home.value = _home.value.copy(busy = false, message = t.message)
            }
        }
    }

    fun loadSamples(sessionId: String) {
        viewModelScope.launch {
            val session = repo.get(sessionId)
            _samples.value = if (session == null) emptyList() else repo.loadSamples(session)
        }
    }

    fun delete(sessionId: String) {
        viewModelScope.launch { repo.delete(sessionId) }
    }

    fun consumeMessage() {
        _home.value = _home.value.copy(message = null)
    }
}

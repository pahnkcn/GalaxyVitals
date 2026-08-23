package app.galaxyvitals.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.galaxyvitals.GalaxyVitalsApp
import app.galaxyvitals.data.userFacingImportError
import app.galaxyvitals.data.wear.WearLinkStatus
import app.galaxyvitals.domain.AnalysisStatus
import app.galaxyvitals.analysis.EcgAnalysisBundle
import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.domain.EcgSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

data class DetailSamplesUiState(
    val sessionId: String? = null,
    val samples: List<EcgSample> = emptyList(),
    val loading: Boolean = false,
)

class HealthTrackViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GalaxyVitalsApp
    private val repo = app.container.ecgRepository
    private val wear = app.container.wearSyncClient

    val sessions: StateFlow<List<EcgSession>> = repo.observeSessions()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _detailSamples = MutableStateFlow(DetailSamplesUiState())
    val detailSamples: StateFlow<DetailSamplesUiState> = _detailSamples.asStateFlow()
    private var sampleLoadJob: Job? = null
    private var wearRefreshJob: Job? = null

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
        wearRefreshJob?.cancel()
        wearRefreshJob = viewModelScope.launch {
            _home.value = _home.value.copy(wear = wear.status())
        }
    }

    fun importUri(uri: Uri) {
        if (!beginBusy()) return
        viewModelScope.launch {
            try {
                val session = repo.importUri(uri)
                _home.value = _home.value.copy(
                    message = "Imported ${session.sessionId}",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Exception) {
                _home.value = _home.value.copy(
                    message = userFacingImportError(t),
                )
            } finally {
                _home.value = _home.value.copy(busy = false)
            }
        }
    }

    fun requestSync() {
        if (!beginBusy()) return
        viewModelScope.launch {
            try {
                val n = wear.requestSyncNow()
                _home.value = _home.value.copy(
                    message = if (n == 0) {
                        "No GalaxyVitals watch node. Install the watch app, or import a csv.gz."
                    } else {
                        "Asked $n watch node(s) to sync."
                    },
                    wear = wear.status(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _home.value = _home.value.copy(message = "Watch sync failed.")
            } finally {
                _home.value = _home.value.copy(busy = false)
            }
        }
    }

    fun loadSamples(sessionId: String) {
        sampleLoadJob?.cancel()
        _detailSamples.value = DetailSamplesUiState(sessionId = sessionId, loading = true)
        sampleLoadJob = viewModelScope.launch {
            try {
                val existing = repo.get(sessionId)
                if (existing != null && (
                        existing.analysisStatus in setOf(
                            AnalysisStatus.NONE,
                            AnalysisStatus.PENDING,
                            AnalysisStatus.FAILED,
                        ) || existing.analysisBundleId != EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID
                    )
                ) {
                    repo.reanalyze(sessionId)
                }
                val session = repo.get(sessionId)
                val loaded = if (session == null) emptyList() else repo.loadSamples(session)
                if (_detailSamples.value.sessionId == sessionId) {
                    _detailSamples.value = DetailSamplesUiState(
                        sessionId = sessionId,
                        samples = loaded,
                        loading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (_detailSamples.value.sessionId == sessionId) {
                    _detailSamples.value = DetailSamplesUiState(sessionId = sessionId)
                }
            }
        }
    }

    fun delete(sessionId: String) {
        if (_detailSamples.value.sessionId == sessionId) {
            sampleLoadJob?.cancel()
            sampleLoadJob = null
            _detailSamples.value = DetailSamplesUiState()
        }
        viewModelScope.launch {
            val remaining = sessions.value.filterNot { it.sessionId == sessionId }
            repo.delete(sessionId)
            try {
                wear.sendDelete(sessionId)
                if (remaining.isEmpty()) wear.sendDeleteAll()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Phone history is already gone; the watch copy is retried on the next delete/sync.
            }
        }
    }

    fun clearWatchHistory() {
        viewModelScope.launch {
            try {
                wear.sendDeleteAll()
                _home.value = _home.value.copy(message = "Asked the watch to remove leftover recordings.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _home.value = _home.value.copy(message = "Could not reach the watch.")
            }
        }
    }

    fun consumeMessage() {
        _home.value = _home.value.copy(message = null)
    }

    private fun beginBusy(): Boolean = synchronized(_home) {
        if (_home.value.busy) {
            false
        } else {
            _home.value = _home.value.copy(busy = true, message = null)
            true
        }
    }
}

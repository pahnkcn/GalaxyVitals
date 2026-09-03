package app.galaxyvitals.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.galaxyvitals.GalaxyVitalsApp
import app.galaxyvitals.R
import app.galaxyvitals.data.userFacingImportError
import app.galaxyvitals.data.wear.WearLinkStatus
import app.galaxyvitals.domain.AnalysisStatus
import app.galaxyvitals.analysis.EcgAnalysisBundle
import app.galaxyvitals.data.protocol.EcgBandwidth
import app.galaxyvitals.domain.EcgSession
import app.galaxyvitals.export.EcgReportBuilder
import app.galaxyvitals.export.EcgReportModel
import app.galaxyvitals.export.EcgReportText
import app.galaxyvitals.export.ExportFormat
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
    val wear: WearLinkStatus = WearLinkStatus(false, emptyList(), ""),
    val message: String? = null,
    val busy: Boolean = false,
)

/**
 * One recording, fully analysed.
 *
 * The screen needs more than a waveform: the beats, the intervals, the HRV and
 * the signal metrics all come off the same parse, so they are loaded together
 * rather than recomputed per card.
 */
data class DetailUiState(
    val sessionId: String? = null,
    val report: EcgReportModel? = null,
    val bandwidth: EcgBandwidth = EcgReportBuilder.REPORT_BANDWIDTH,
    val loading: Boolean = false,
)

data class ExportUiState(
    val running: Boolean = false,
    val failed: Boolean = false,
    /** Handed to the share sheet once, then consumed. */
    val share: Intent? = null,
)

class HealthTrackViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GalaxyVitalsApp
    private val repo = app.container.ecgRepository
    private val wear = app.container.wearSyncClient
    private val exporter = app.container.ecgExporter

    init {
        // Exports are copies of health data in a shareable directory. They should
        // not outlive the share that produced them by more than a day.
        exporter.purgeStaleExports()
    }

    val sessions: StateFlow<List<EcgSession>> = repo.observeSessions()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _detail = MutableStateFlow(DetailUiState())
    val detail: StateFlow<DetailUiState> = _detail.asStateFlow()

    private val _export = MutableStateFlow(ExportUiState())
    val export: StateFlow<ExportUiState> = _export.asStateFlow()

    private var sampleLoadJob: Job? = null
    private var wearRefreshJob: Job? = null
    private var exportJob: Job? = null

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
        _home.value = _home.value.copy(
            wear = _home.value.wear.copy(note = string(R.string.msg_wear_checking)),
        )
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
                    message = string(R.string.msg_imported, session.sessionId),
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
                        string(R.string.msg_no_watch_node)
                    } else {
                        string(R.string.msg_sync_asked, n)
                    },
                    wear = wear.status(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _home.value = _home.value.copy(message = string(R.string.msg_sync_failed))
            } finally {
                _home.value = _home.value.copy(busy = false)
            }
        }
    }

    fun loadSamples(sessionId: String) {
        loadReport(sessionId, _detail.value.bandwidth.takeIf { _detail.value.sessionId == sessionId })
    }

    /** Redraws and re-measures the recording at a different bandwidth. */
    fun setBandwidth(bandwidth: EcgBandwidth) {
        val sessionId = _detail.value.sessionId ?: return
        if (_detail.value.bandwidth == bandwidth) return
        loadReport(sessionId, bandwidth)
    }

    private fun loadReport(sessionId: String, bandwidth: EcgBandwidth?) {
        val requested = bandwidth ?: EcgReportBuilder.REPORT_BANDWIDTH
        sampleLoadJob?.cancel()
        // Hold the strip that is already on screen while the new bandwidth is
        // computed. Blanking it makes a filter toggle look like a reload.
        val carried = _detail.value.report?.takeIf { _detail.value.sessionId == sessionId }
        _detail.value = DetailUiState(
            sessionId = sessionId,
            report = carried,
            bandwidth = requested,
            loading = true,
        )
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
                val report = session?.let {
                    repo.loadReport(it, requested, exporter.appVersion())
                }
                if (_detail.value.sessionId == sessionId) {
                    _detail.value = DetailUiState(
                        sessionId = sessionId,
                        report = report,
                        bandwidth = requested,
                        loading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (_detail.value.sessionId == sessionId) {
                    _detail.value = DetailUiState(sessionId = sessionId, bandwidth = requested)
                }
            }
        }
    }

    /**
     * Writes the recording out and hands back a share intent.
     *
     * The report is rebuilt here rather than reusing the loaded one, because the
     * note is typed at export time and is deliberately never stored.
     */
    fun exportSession(
        sessionId: String,
        format: ExportFormat,
        note: String,
        text: EcgReportText,
        chooserTitle: String,
    ) {
        if (_export.value.running) return
        exportJob?.cancel()
        _export.value = ExportUiState(running = true)
        exportJob = viewModelScope.launch {
            try {
                val session = repo.get(sessionId) ?: error("recording is gone")
                val report = repo.loadReport(
                    session = session,
                    bandwidth = _detail.value.bandwidth,
                    appVersion = exporter.appVersion(),
                    note = note,
                ) ?: error("recording could not be read")
                val exported = exporter.export(
                    report = report,
                    text = text,
                    format = format,
                    sourceFile = repo.storedFile(session),
                )
                _export.value = ExportUiState(
                    share = exporter.shareIntent(exported, chooserTitle),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _export.value = ExportUiState(failed = true)
            }
        }
    }

    fun consumeShare() {
        _export.value = _export.value.copy(share = null)
    }

    fun consumeExportFailure() {
        _export.value = _export.value.copy(failed = false)
    }

    fun delete(sessionId: String) {
        if (_detail.value.sessionId == sessionId) {
            sampleLoadJob?.cancel()
            sampleLoadJob = null
            _detail.value = DetailUiState()
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
                _home.value = _home.value.copy(message = string(R.string.msg_watch_cleanup))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _home.value = _home.value.copy(message = string(R.string.msg_watch_unreachable))
            }
        }
    }

    fun consumeMessage() {
        _home.value = _home.value.copy(message = null)
    }

    private fun string(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    private fun beginBusy(): Boolean = synchronized(_home) {
        if (_home.value.busy) {
            false
        } else {
            _home.value = _home.value.copy(busy = true, message = null)
            true
        }
    }
}

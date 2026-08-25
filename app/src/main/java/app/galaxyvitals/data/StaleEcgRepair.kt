package app.galaxyvitals.data

import app.galaxyvitals.data.local.EcgSessionEntity
import app.galaxyvitals.domain.AnalysisStatus

object StaleEcgRepair {
    fun isStale(status: String, bundleId: String?, currentBundleId: String): Boolean =
        status == AnalysisStatus.NONE.name ||
            status == AnalysisStatus.PENDING.name ||
            bundleId == null ||
            bundleId != currentBundleId

    fun ordered(rows: List<EcgSessionEntity>, currentBundleId: String): List<EcgSessionEntity> =
        rows.filter { row ->
            isStale(row.analysisStatus, row.analysisBundleId, currentBundleId)
        }.sortedByDescending { it.tsStartMs }
}

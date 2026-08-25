package app.galaxyvitals.data

import app.galaxyvitals.analysis.EcgAnalysisBundle
import app.galaxyvitals.data.local.EcgSessionEntity
import app.galaxyvitals.domain.AnalysisStatus
import app.galaxyvitals.domain.EcgSource
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StaleEcgRepairTest {
    private val current = EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID

    @Test
    fun selectsNonePendingNullBundleAndMismatchedBundle() {
        val rows = listOf(
            entity("ok-current", AnalysisStatus.OK, current),
            entity("failed-null", AnalysisStatus.FAILED, null),
            entity("none", AnalysisStatus.NONE, null),
            entity("pending", AnalysisStatus.PENDING, null),
            entity("ok-old", AnalysisStatus.OK, "legacy-bundle"),
            entity("lq-current", AnalysisStatus.LOW_QUALITY, current),
            entity("failed-current", AnalysisStatus.FAILED, current),
        )

        val selected = StaleEcgRepair.ordered(rows, current).map { it.sessionId }

        assertThat(selected).containsExactly("none", "pending", "failed-null", "ok-old")
        assertThat(selected).containsNoneIn(listOf("ok-current", "lq-current", "failed-current"))
    }

    @Test
    fun ordersNewestFirst() {
        val older = entity("older", AnalysisStatus.NONE, null, tsStartMs = 10)
        val newer = entity("newer", AnalysisStatus.PENDING, null, tsStartMs = 20)

        val ordered = StaleEcgRepair.ordered(listOf(older, newer), current)

        assertThat(ordered.map { it.sessionId }).containsExactly("newer", "older").inOrder()
    }

    @Test
    fun secondPassSkipsTerminalCurrentBundleRows() {
        val stale = listOf(
            entity("stale-none", AnalysisStatus.NONE, null, tsStartMs = 10),
            entity("stale-old", AnalysisStatus.OK, "legacy-bundle", tsStartMs = 20),
            entity("stale-failed-null", AnalysisStatus.FAILED, null, tsStartMs = 30),
        )
        val firstPass = StaleEcgRepair.ordered(stale, current)
        assertThat(firstPass).hasSize(stale.size)

        var analyzeCalls = 0
        val analyzed = firstPass.mapIndexed { index, row ->
            analyzeCalls++
            val status = if (index % 2 == 0) AnalysisStatus.FAILED else AnalysisStatus.OK
            row.copy(
                analysisStatus = status.name,
                analysisBundleId = current,
            )
        }

        assertThat(analyzeCalls).isEqualTo(firstPass.size)
        assertThat(StaleEcgRepair.ordered(analyzed, current)).isEmpty()

        val secondPassIds = StaleEcgRepair.ordered(analyzed, current).map { it.sessionId }
        assertThat(secondPassIds).containsNoneIn(analyzed.map { it.sessionId })
        assertThat(secondPassIds).isEmpty()
    }

    private fun entity(
        sessionId: String,
        status: AnalysisStatus,
        bundleId: String?,
        tsStartMs: Long = 1L,
    ) = EcgSessionEntity(
        sessionId = sessionId,
        filePath = "ecg_$sessionId.csv.gz",
        tsStartMs = tsStartMs,
        srHz = 500,
        nSamples = 4_000,
        durationSec = 8.0,
        hrMedian = null,
        hrMin = null,
        hrMax = null,
        hrCoveragePct = 0.0,
        usablePct = 100.0,
        wrist = "LEFT",
        signFactor = 1,
        polarityNormalized = false,
        unit = "mV",
        watchInfo = "watch",
        source = EcgSource.WEAR.name,
        createdAtMs = 1L,
        analysisStatus = status.name,
        analysisBundleId = bundleId,
    )
}

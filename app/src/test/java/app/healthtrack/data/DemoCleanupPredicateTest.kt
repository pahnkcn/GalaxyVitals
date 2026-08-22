package app.healthtrack.data

import app.healthtrack.data.local.EcgSessionEntity
import app.healthtrack.domain.EcgSource
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DemoCleanupPredicateTest {
    @Test
    fun acceptsExplicitDemoAndExactLegacyPhoneSignature() {
        assertThat(isSafeDemoCleanupCandidate(entity(captureSource = "DEMO", source = "WEAR"))).isTrue()
        assertThat(isSafeDemoCleanupCandidate(entity())).isTrue()
        assertThat(isSafeDemoCleanupCandidate(entity(sessionId = "demo-123"))).isTrue()
    }

    @Test
    fun preservesRealHardwareImportAndLegacyRows() {
        assertThat(
            isSafeDemoCleanupCandidate(
                entity(sessionId = "hardware", source = EcgSource.WEAR.name, captureSource = "HARDWARE"),
            ),
        ).isFalse()
        assertThat(isSafeDemoCleanupCandidate(entity(sessionId = "imported"))).isFalse()
        assertThat(isSafeDemoCleanupCandidate(entity(watchInfo = "real-watch"))).isFalse()
        assertThat(isSafeDemoCleanupCandidate(entity(tsStartMs = 1_700_000_000_001L))).isFalse()
        assertThat(isSafeDemoCleanupCandidate(entity(nSamples = 15_000))).isFalse()
        assertThat(isSafeDemoCleanupCandidate(entity(captureSource = "LEGACY", sessionId = "legacy"))).isFalse()
    }

    private fun entity(
        sessionId: String = "demo",
        source: String = EcgSource.IMPORT.name,
        captureSource: String = "IMPORT",
        watchInfo: String = "demo",
        tsStartMs: Long = 1_700_000_000_000L,
        nSamples: Int = 4_000,
    ) = EcgSessionEntity(
        sessionId = sessionId,
        filePath = "ecg_$sessionId.csv.gz",
        tsStartMs = tsStartMs,
        srHz = 500,
        nSamples = nSamples,
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
        watchInfo = watchInfo,
        source = source,
        createdAtMs = 1L,
        captureSource = captureSource,
    )
}

package app.galaxyvitals.ui

import app.galaxyvitals.domain.AnalysisStatus
import app.galaxyvitals.domain.EcgSession
import app.galaxyvitals.domain.EcgSource
import app.galaxyvitals.domain.Wrist
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FormattersTest {
    @Test
    fun roundsNominalThirtySecondCaptureDuration() {
        val session = session(
            status = AnalysisStatus.NONE,
            label = null,
            confidence = null,
            findings = "",
            durationSec = 29.998,
        )

        assertThat(session.durationLabel()).isEqualTo("30s")
    }

    @Test
    fun lowQualityDoesNotDisplayStaleRhythmDecision() {
        val session = session(
            status = AnalysisStatus.LOW_QUALITY,
            label = "A",
            confidence = 0.99f,
            findings = "AFIB:0.99",
        )

        assertThat(session.naoTitle()).isEqualTo("Low quality")
        assertThat(session.naoConfidenceLabel()).isEmpty()
        assertThat(session.findingRows()).isEmpty()
    }

    @Test
    fun okDecisionStillDisplays() {
        val session = session(
            status = AnalysisStatus.OK,
            label = "N",
            confidence = 0.91f,
            findings = "",
        )

        assertThat(session.naoTitle()).isEqualTo("Normal")
        assertThat(session.naoConfidenceLabel()).isEqualTo("91%")
    }

    private fun session(
        status: AnalysisStatus,
        label: String?,
        confidence: Float?,
        findings: String,
        durationSec: Double = 1.0,
    ) = EcgSession(
        sessionId = "test",
        filePath = "test.csv.gz",
        tsStartMs = 0,
        srHz = 500,
        nSamples = 10,
        durationSec = durationSec,
        hrMedian = 70.0,
        hrMin = 68,
        hrMax = 72,
        hrCoveragePct = 100.0,
        usablePct = 100.0,
        wrist = Wrist.LEFT,
        signFactor = 1,
        polarityNormalized = false,
        unit = "mV",
        watchInfo = "test",
        source = EcgSource.IMPORT,
        createdAtMs = 0,
        analysisStatus = status,
        naoLabel = label,
        naoConfidence = confidence,
        findings = findings,
    )
}

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
    fun hrLabelRoundsMedianToNearestInt() {
        assertThat(session(ecgHrMedian = 72.6).hrLabel()).isEqualTo("73")
        assertThat(session(ecgHrMedian = 72.4).hrLabel()).isEqualTo("72")
        assertThat(session(hrMedian = 71.6, ecgHrMedian = null).hrLabel()).isEqualTo("72")
        assertThat(session(hrMedian = null, ecgHrMedian = null).hrLabel()).isEqualTo("—")
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

    @Test
    fun failedAnalysisStillShowsEcgBpmOnHistory() {
        val session = session(
            status = AnalysisStatus.FAILED,
            label = null,
            confidence = null,
            findings = "",
            ecgHrMedian = 81.2,
        )

        assertThat(session.hrLabel()).isEqualTo("81")
        assertThat(session.naoTitle()).isEqualTo("Not analysed")
    }

    private fun session(
        status: AnalysisStatus = AnalysisStatus.NONE,
        label: String? = null,
        confidence: Float? = null,
        findings: String = "",
        durationSec: Double = 1.0,
        hrMedian: Double? = 70.0,
        ecgHrMedian: Double? = null,
    ) = EcgSession(
        sessionId = "test",
        filePath = "test.csv.gz",
        tsStartMs = 0,
        srHz = 500,
        nSamples = 10,
        durationSec = durationSec,
        hrMedian = hrMedian,
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
        ecgHrMedian = ecgHrMedian,
    )
}

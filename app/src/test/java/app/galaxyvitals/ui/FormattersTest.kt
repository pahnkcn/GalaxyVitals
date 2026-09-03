package app.galaxyvitals.ui

import app.galaxyvitals.R
import app.galaxyvitals.domain.AnalysisStatus
import app.galaxyvitals.domain.EcgSession
import app.galaxyvitals.domain.EcgSource
import app.galaxyvitals.domain.Wrist
import app.galaxyvitals.data.protocol.LiveBpmSummarizer
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FormattersTest {
    @Test
    fun minutesAndSecondsSplitAtSixty() {
        val session = session(durationSec = 95.0)

        assertThat(session.durationMinutes()).isEqualTo(1)
        assertThat(session.durationSeconds()).isEqualTo(35)
    }

    @Test
    fun roundsNominalThirtySecondCaptureDuration() {
        val session = session(
            status = AnalysisStatus.NONE,
            label = null,
            confidence = null,
            findings = "",
            durationSec = 29.998,
        )

        assertThat(session.durationMinutes()).isEqualTo(0)
        assertThat(session.durationSeconds()).isEqualTo(30)
    }

    @Test
    fun lowQualityDoesNotDisplayStaleRhythmDecision() {
        val session = session(
            status = AnalysisStatus.LOW_QUALITY,
            label = "A",
            confidence = 0.99f,
            findings = "AFIB:0.99",
        )

        assertThat(session.naoTitleRes()).isEqualTo(R.string.verdict_low_quality)
        assertThat(session.naoConfidenceLabel()).isEmpty()
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

        assertThat(session.naoTitleRes()).isEqualTo(R.string.verdict_regular)
        assertThat(session.naoConfidenceLabel()).isEqualTo("91%")
    }

    @Test
    fun indeterminateDoesNotDisplayStaleRhythmDecision() {
        val session = session(
            status = AnalysisStatus.INDETERMINATE,
            label = "N",
            confidence = 0.91f,
            findings = "N:0.91",
            ecgHrMedian = 72.4,
        )

        assertThat(session.naoTitleRes()).isEqualTo(R.string.verdict_indeterminate)
        assertThat(session.naoConfidenceLabel()).isEmpty()
        assertThat(session.hrLabel()).isEqualTo("72")
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
        assertThat(session.naoTitleRes()).isEqualTo(R.string.verdict_not_analysed)
    }

    @Test
    fun historyPrimaryUsesEcgHrMedianNotLiveMedian() {
        val session = session(
            hrMedian = 50.0,
            ecgHrMedian = 72.4,
            liveBpmMedian = 120.0,
        )

        assertThat(session.hrLabel()).isEqualTo("72")
    }

    @Test
    fun samsungPrimaryAlgorithmUsesProcessedLiveMedianBeforeEcgMedian() {
        val session = session(
            hrMedian = 50.0,
            ecgHrMedian = 72.4,
            liveBpmMedian = 119.6,
            liveBpmAlgorithmId = LiveBpmSummarizer.SAMSUNG_PRIMARY_ALGORITHM_ID,
        )

        assertThat(session.hrLabel()).isEqualTo("120")
    }

    private fun session(
        status: AnalysisStatus = AnalysisStatus.NONE,
        label: String? = null,
        confidence: Float? = null,
        findings: String = "",
        durationSec: Double = 1.0,
        hrMedian: Double? = 70.0,
        ecgHrMedian: Double? = null,
        liveBpmMedian: Double? = null,
        liveBpmAlgorithmId: String? = null,
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
        liveBpmMedian = liveBpmMedian,
        liveBpmAlgorithmId = liveBpmAlgorithmId,
    )
}

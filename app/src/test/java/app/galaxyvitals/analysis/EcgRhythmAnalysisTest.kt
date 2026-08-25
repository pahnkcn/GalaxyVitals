package app.galaxyvitals.analysis

import app.galaxyvitals.data.protocol.NaoDecision
import app.galaxyvitals.data.protocol.NaoLabel
import app.galaxyvitals.domain.AnalysisStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class EcgRhythmAnalysisTest {
    @Test
    fun throwingClassifierStillReturnsFailedWithBpmNear72() {
        val result = EcgRhythmAnalysis.analyze(EcgAnalysisFixtures.clean72BpmRecording()) {
            throw RuntimeException("boom")
        }

        assertThat(result.status).isEqualTo(AnalysisStatus.FAILED)
        assertThat(result.decision).isNull()
        assertThat(result.ecgHrMedian).isNotNull()
        assertThat(result.ecgHrMedian!!).isWithin(3.0).of(72.0)
        assertThat(result.quality).isNotNull()
        assertThat(result.analysisBundleId).isEqualTo(EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID)
        assertThat(result.failureStage).isEqualTo(ModelFailureStage.INFERENCE)
        assertThat(result.cause).isNotNull()
    }

    @Test
    fun lowQualityDoesNotCallClassifier() {
        val called = AtomicBoolean(false)
        val result = EcgRhythmAnalysis.analyze(EcgAnalysisFixtures.lowQualityRecording()) {
            called.set(true)
            throw AssertionError("classifier must not be called")
        }

        assertThat(result.status).isEqualTo(AnalysisStatus.LOW_QUALITY)
        assertThat(called.get()).isFalse()
        assertThat(result.quality).isNotNull()
        assertThat(result.analysisBundleId).isEqualTo(EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID)
    }

    @Test
    fun successfulClassifierReturnsBpmQualityAndNao() {
        val decision = NaoDecision(
            label = NaoLabel.N,
            confidence = 0.9f,
            pNormal = 0.9f,
            pAf = 0.05f,
            pOther = 0.05f,
            topFindings = emptyList(),
        )
        val result = EcgRhythmAnalysis.analyze(EcgAnalysisFixtures.clean72BpmRecording()) { decision }

        assertThat(result.status).isEqualTo(AnalysisStatus.OK)
        assertThat(result.decision!!.label).isEqualTo(NaoLabel.N)
        assertThat(result.ecgHrMedian).isNotNull()
        assertThat(result.ecgHrMedian!!).isWithin(3.0).of(72.0)
        assertThat(result.quality).isNotNull()
        assertThat(result.quality!!.usableForAnalysis).isTrue()
        assertThat(result.analysisBundleId).isEqualTo(EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID)
        assertThat(result.failureStage).isNull()
    }
}

package app.galaxyvitals.analysis

import app.galaxyvitals.data.protocol.Nao3Preprocess
import app.galaxyvitals.data.protocol.NaoDecision
import app.galaxyvitals.data.protocol.NaoLabel
import app.galaxyvitals.domain.AnalysisStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class EcgRhythmAnalysisTest {
    private val classifiedNormal = Nao3Verdict.Classified(
        NaoDecision(
            label = NaoLabel.N,
            confidence = 0.9f,
            pNormal = 0.9f,
            pAf = 0.05f,
            pOther = 0.05f,
            topFindings = emptyList(),
        ),
    )

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
    fun shortRecordingIsIndeterminateAndDoesNotCallClassifier() {
        val called = AtomicBoolean(false)
        val result = EcgRhythmAnalysis.analyze(EcgAnalysisFixtures.shortClean20sRecording()) {
            called.set(true)
            throw AssertionError("classifier must not be called")
        }

        assertThat(result.status).isEqualTo(AnalysisStatus.INDETERMINATE)
        assertThat(called.get()).isFalse()
        assertThat(result.decision).isNull()
        assertThat(result.ecgHrMedian).isNotNull()
        assertThat(result.ecgHrMedian!!).isWithin(3.0).of(72.0)
        assertThat(result.quality).isNotNull()
        assertThat(result.analysisBundleId).isEqualTo(EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID)
    }

    @Test
    fun contaminatedModelIntervalIsLowQualityAndDoesNotCallClassifier() {
        val called = AtomicBoolean(false)
        val result = EcgRhythmAnalysis.analyze(EcgAnalysisFixtures.contaminated30sRecording()) {
            called.set(true)
            throw AssertionError("classifier must not be called")
        }

        assertThat(result.status).isEqualTo(AnalysisStatus.LOW_QUALITY)
        assertThat(called.get()).isFalse()
        assertThat(result.decision).isNull()
        assertThat(result.ecgHrMedian).isNotNull()
        assertThat(result.quality).isNotNull()
        assertThat(result.analysisBundleId).isEqualTo(EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID)
    }

    @Test
    fun successfulClassifierReturnsBpmQualityAndNao() {
        val inputSize = AtomicInteger(-1)
        val result = EcgRhythmAnalysis.analyze(EcgAnalysisFixtures.clean72BpmRecording()) { input ->
            inputSize.set(input.size)
            classifiedNormal
        }

        assertThat(result.status).isEqualTo(AnalysisStatus.OK)
        assertThat(result.decision!!.label).isEqualTo(NaoLabel.N)
        assertThat(inputSize.get()).isEqualTo(Nao3Preprocess.INPUT_SAMPLES)
        assertThat(result.ecgHrMedian).isNotNull()
        assertThat(result.ecgHrMedian!!).isWithin(3.0).of(72.0)
        assertThat(result.quality).isNotNull()
        assertThat(result.quality!!.usableForAnalysis).isTrue()
        assertThat(result.analysisBundleId).isEqualTo(EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID)
        assertThat(result.failureStage).isNull()
    }

    @Test
    fun modelAbstentionIsIndeterminateButKeepsBpm() {
        val result = EcgRhythmAnalysis.analyze(EcgAnalysisFixtures.clean72BpmRecording()) {
            Nao3Verdict.Indeterminate
        }

        assertThat(result.status).isEqualTo(AnalysisStatus.INDETERMINATE)
        assertThat(result.decision).isNull()
        assertThat(result.ecgHrMedian).isNotNull()
        assertThat(result.ecgHrMedian!!).isWithin(3.0).of(72.0)
        assertThat(result.quality).isNotNull()
        assertThat(result.analysisBundleId).isEqualTo(EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID)
        assertThat(result.failureStage).isNull()
    }

    @Test
    fun missingPolicyIsIndeterminateAndDoesNotShowNao() {
        val result = EcgRhythmAnalysis.analyze(EcgAnalysisFixtures.clean72BpmRecording()) {
            throw PolicyIntegrityException(IllegalStateException("policy hash mismatch"))
        }

        assertThat(result.status).isEqualTo(AnalysisStatus.INDETERMINATE)
        assertThat(result.decision).isNull()
        assertThat(result.ecgHrMedian).isNotNull()
        assertThat(result.ecgHrMedian!!).isWithin(3.0).of(72.0)
        assertThat(result.analysisBundleId).isEqualTo(EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID)
        assertThat(result.failureStage).isNull()
    }

    @Test
    fun centerThirtySecondsIsScoredWhenEdgesAreContaminated() {
        val called = AtomicBoolean(false)
        val result = EcgRhythmAnalysis.analyze(
            EcgAnalysisFixtures.fortySecondRecordingWithDirtyPrefix(),
        ) { input ->
            called.set(true)
            assertThat(input).hasLength(Nao3Preprocess.INPUT_SAMPLES)
            classifiedNormal
        }

        assertThat(called.get()).isTrue()
        assertThat(result.status).isEqualTo(AnalysisStatus.OK)
        assertThat(result.decision!!.label).isEqualTo(NaoLabel.N)
        assertThat(result.ecgHrMedian).isNotNull()
    }
}

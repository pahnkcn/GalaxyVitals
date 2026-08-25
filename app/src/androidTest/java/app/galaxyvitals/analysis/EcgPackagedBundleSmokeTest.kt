package app.galaxyvitals.analysis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.galaxyvitals.domain.AnalysisStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EcgPackagedBundleSmokeTest {
    @Test
    fun packagedNao3BundleLoadsAndAnalyzesClean72Bpm() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bundle = EcgAnalysisBundle.load(context)

        assertThat(bundle.compatibilityId).isEqualTo("ecg-nao3-student-256hz-v1")
        assertThat(bundle.model.sha256)
            .isEqualTo("7400a2352c79275d5a4860a76a684cc0b6140e8385572de5a68027f7343a20ac")
        assertThat(bundle.filters.sha256)
            .isEqualTo("1c72ace362fdff4ce0dd8d3ac0cbc7e898300a0b866fb53ef48cdc6e6d95dd52")

        val parsed = EcgAndroidTestFixtures.clean72BpmRecording()
        assertThat(parsed.schemaVersion).isEqualTo(2)
        assertThat(parsed.srHz).isEqualTo(500)
        assertThat(parsed.samples).hasSize(15_000)
        assertThat(parsed.hrMedian).isNull()
        assertThat(parsed.samples.all { it.hrBpm == null }).isTrue()

        EcgRhythmEngine(context).use { engine ->
            val result = engine.analyze(parsed)
            assertThat(result.status).isAnyOf(AnalysisStatus.OK, AnalysisStatus.FAILED)
            assertThat(result.ecgHrMedian).isNotNull()
            assertThat(result.ecgHrMedian!!).isWithin(8.0).of(72.0)
            assertThat(result.quality).isNotNull()
            assertThat(result.analysisBundleId)
                .isEqualTo(EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID)
            if (result.status == AnalysisStatus.OK) {
                val decision = result.decision
                assertThat(decision).isNotNull()
                assertThat(decision!!.pNormal).isFinite()
                assertThat(decision.pAf).isFinite()
                assertThat(decision.pOther).isFinite()
            } else {
                assertThat(result.decision).isNull()
            }
        }
    }
}

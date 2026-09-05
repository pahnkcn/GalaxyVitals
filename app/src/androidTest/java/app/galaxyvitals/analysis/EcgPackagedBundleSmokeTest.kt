package app.galaxyvitals.analysis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.galaxyvitals.data.protocol.NaoLabel
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

        assertThat(bundle.compatibilityId).isEqualTo("ecg-nao3-student-256hz-v4")
        assertThat(bundle.model.sha256)
            .isEqualTo("c98a8356837673980d3622d45156e78bb898bca587bc456091544e1d6461fdba")
        assertThat(bundle.filters.sha256)
            .isEqualTo("1c72ace362fdff4ce0dd8d3ac0cbc7e898300a0b866fb53ef48cdc6e6d95dd52")
        assertThat(bundle.policy.sha256)
            .isEqualTo("cfd0be06f767396591407ed371521f487d94ac821eec448f0876312b96bad066")
        assertThat(bundle.split.sha256)
            .isEqualTo("8b17173567d3c4cd7d8fdf1288d4a0e78ecaf84609af9c668b44ec2542a8d7af")

        val parsed = EcgAndroidTestFixtures.clean72BpmRecording()
        assertThat(parsed.schemaVersion).isEqualTo(2)
        assertThat(parsed.srHz).isEqualTo(500)
        assertThat(parsed.samples).hasSize(15_000)
        assertThat(parsed.hrMedian).isNull()
        assertThat(parsed.samples.all { it.hrBpm == null }).isTrue()

        EcgRhythmEngine(context).use { engine ->
            val result = engine.analyze(parsed)

            // The model must actually run. FAILED would mean the bundle, the
            // interpreter, or the preprocessing broke.
            assertThat(result.status).isAnyOf(AnalysisStatus.OK, AnalysisStatus.INDETERMINATE)
            assertThat(result.ecgHrMedian).isNotNull()
            assertThat(result.ecgHrMedian!!).isWithin(8.0).of(72.0)
            assertThat(result.quality).isNotNull()
            assertThat(result.analysisBundleId)
                .isEqualTo(EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID)

            // N and A can both be decided now; O still abstains by design. The
            // invariant is that a decision is never O -- not the specific verdict
            // on this fixture, which sits close to its threshold and would flip
            // on any small numeric change.
            result.decision?.let { assertThat(it.label).isNotEqualTo(NaoLabel.O) }
        }
    }

    @Test
    fun everyEnabledClassCarriesADemonstratedPrecision() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val policy = EcgAnalysisBundle.load(context).decisionPolicy

        // A class may only be decidable if it demonstrated at least the policy's
        // own precision target on the sealed CinC 2017 evaluation split. That is
        // the invariant; which classes happen to qualify is a property of the
        // model and changes between builds. N measured 0.9269 and A measured
        // 0.9118 over 1,781 record-disjoint records -- the first build in which A
        // cleared the gate at all, after 0.8438 and 0.8767 in earlier rounds.
        for ((_, entry) in policy.classes) {
            if (entry.alwaysAbstain) continue
            assertThat(entry.minProbability).isNotNull()
            assertThat(entry.demonstratedPrecision).isNotNull()
            assertThat(entry.demonstratedPrecision!!).isAtLeast(policy.precisionTarget)
        }

        assertThat(policy.classes.getValue(NaoLabel.N).alwaysAbstain).isFalse()

        // O abstains by design whatever it measures: the app renders it as
        // "No clear result", which is what abstention already shows.
        assertThat(policy.classes.getValue(NaoLabel.O).alwaysAbstain).isTrue()

        assertThat(policy.splitStatus).isNotEqualTo("calibration_data_absent")
    }
}

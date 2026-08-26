package app.galaxyvitals.analysis

import app.galaxyvitals.data.protocol.NaoLabel
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class Nao3PostprocessorTest {
    private val permissive = Nao3DecisionPolicy(
        precisionTarget = 0.90,
        minMargin = 0.05f,
        classes = mapOf(
            NaoLabel.N to Nao3ClassPolicy(alwaysAbstain = false, minProbability = 0.50f),
            NaoLabel.A to Nao3ClassPolicy(alwaysAbstain = false, minProbability = 0.50f),
            NaoLabel.O to Nao3ClassPolicy(alwaysAbstain = false, minProbability = 0.50f),
        ),
    )

    @Test
    fun stableSoftmaxProducesFiniteNormalizedProbabilities() {
        val verdict = Nao3Postprocessor.fromLogits(floatArrayOf(1_000f, 999f, -1_000f), permissive)
        val decision = (verdict as Nao3Verdict.Classified).decision

        assertThat(decision.label).isEqualTo(NaoLabel.N)
        assertThat(decision.pNormal).isFinite()
        assertThat(decision.pAf).isFinite()
        assertThat(decision.pOther).isFinite()
        assertThat(decision.pNormal + decision.pAf + decision.pOther).isWithin(1e-6f).of(1f)
        assertThat(decision.confidence).isEqualTo(decision.pNormal)
        assertThat(decision.topFindings).isEmpty()
    }

    @Test
    fun argmaxUsesNaoLabelOrder() {
        val cases = listOf(
            floatArrayOf(5f, 0f, 0f) to NaoLabel.N,
            floatArrayOf(0f, 5f, 0f) to NaoLabel.A,
            floatArrayOf(0f, 0f, 5f) to NaoLabel.O,
        )

        cases.forEach { (logits, expected) ->
            val verdict = Nao3Postprocessor.fromLogits(logits, permissive)
            assertThat((verdict as Nao3Verdict.Classified).decision.label).isEqualTo(expected)
        }
    }

    @Test
    fun equalLogitsDoNotBecomeNormal() {
        val verdict = Nao3Postprocessor.fromLogits(floatArrayOf(0f, 0f, 0f), permissive)

        assertThat(verdict).isEqualTo(Nao3Verdict.Indeterminate)
    }

    @Test
    fun nearTieDoesNotBecomeAClassLabel() {
        val verdict = Nao3Postprocessor.fromLogits(floatArrayOf(1.00f, 0.99f, 0.00f), permissive)

        assertThat(verdict).isEqualTo(Nao3Verdict.Indeterminate)
    }

    @Test
    fun belowMinProbabilityAbstains() {
        val strict = permissive.copy(
            classes = permissive.classes.mapValues { (_, policy) ->
                policy.copy(minProbability = 0.99f)
            },
        )

        val verdict = Nao3Postprocessor.fromLogits(floatArrayOf(1_000f, 999f, -1_000f), strict)

        assertThat(verdict).isEqualTo(Nao3Verdict.Indeterminate)
    }

    @Test
    fun alwaysAbstainPolicyNeverEmitsNao() {
        val verdict = Nao3Postprocessor.fromLogits(
            floatArrayOf(12f, -4f, -8f),
            Nao3DecisionPolicy.alwaysAbstain(),
        )

        assertThat(verdict).isEqualTo(Nao3Verdict.Indeterminate)
    }

    @Test
    fun rejectsWrongOutputSize() {
        assertThrows(IllegalArgumentException::class.java) {
            Nao3Postprocessor.fromLogits(floatArrayOf(1f, 2f), permissive)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Nao3Postprocessor.fromLogits(floatArrayOf(1f, 2f, 3f, 4f), permissive)
        }
    }

    @Test
    fun rejectsNonFiniteLogits() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                Nao3Postprocessor.fromLogits(floatArrayOf(0f, invalid, 0f), permissive)
            }
        }
    }
}

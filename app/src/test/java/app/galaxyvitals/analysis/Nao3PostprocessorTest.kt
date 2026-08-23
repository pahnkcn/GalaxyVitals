package app.galaxyvitals.analysis

import app.galaxyvitals.data.protocol.NaoLabel
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class Nao3PostprocessorTest {
    @Test
    fun stableSoftmaxProducesFiniteNormalizedProbabilities() {
        val decision = Nao3Postprocessor.fromLogits(floatArrayOf(1_000f, 999f, -1_000f))

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
            floatArrayOf(3f, 2f, 1f) to NaoLabel.N,
            floatArrayOf(1f, 3f, 2f) to NaoLabel.A,
            floatArrayOf(2f, 1f, 3f) to NaoLabel.O,
        )

        cases.forEach { (logits, expected) ->
            assertThat(Nao3Postprocessor.fromLogits(logits).label).isEqualTo(expected)
        }
    }

    @Test
    fun rejectsWrongOutputSize() {
        assertThrows(IllegalArgumentException::class.java) {
            Nao3Postprocessor.fromLogits(floatArrayOf(1f, 2f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Nao3Postprocessor.fromLogits(floatArrayOf(1f, 2f, 3f, 4f))
        }
    }

    @Test
    fun rejectsNonFiniteLogits() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                Nao3Postprocessor.fromLogits(floatArrayOf(0f, invalid, 0f))
            }
        }
    }
}

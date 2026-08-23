package app.galaxyvitals.analysis

import app.galaxyvitals.data.protocol.NaoDecision
import app.galaxyvitals.data.protocol.NaoLabel
import kotlin.math.exp

object Nao3Postprocessor {
    private val labels = listOf(NaoLabel.N, NaoLabel.A, NaoLabel.O)

    fun fromLogits(logits: FloatArray): NaoDecision {
        require(logits.size == labels.size) {
            "NAO3 must return exactly ${labels.size} logits"
        }
        require(logits.all(Float::isFinite)) { "NAO3 logits must be finite" }

        val maxLogit = logits.max()
        val exponentials = DoubleArray(logits.size) { index ->
            exp((logits[index] - maxLogit).toDouble())
        }
        val denominator = exponentials.sum()
        require(denominator.isFinite() && denominator > 0.0) {
            "NAO3 logits produced invalid probabilities"
        }
        val probabilities = FloatArray(exponentials.size) { index ->
            (exponentials[index] / denominator).toFloat()
        }
        require(probabilities.all(Float::isFinite)) {
            "NAO3 logits produced non-finite probabilities"
        }

        var bestIndex = 0
        for (index in 1 until probabilities.size) {
            if (probabilities[index] > probabilities[bestIndex]) bestIndex = index
        }
        return NaoDecision(
            label = labels[bestIndex],
            confidence = probabilities[bestIndex],
            pNormal = probabilities[0],
            pAf = probabilities[1],
            pOther = probabilities[2],
            topFindings = emptyList(),
        )
    }
}

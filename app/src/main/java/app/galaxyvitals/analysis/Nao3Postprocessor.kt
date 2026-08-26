package app.galaxyvitals.analysis

import app.galaxyvitals.data.protocol.NaoDecision
import app.galaxyvitals.data.protocol.NaoLabel
import kotlin.math.exp

sealed class Nao3Verdict {
    data class Classified(val decision: NaoDecision) : Nao3Verdict()
    data object Indeterminate : Nao3Verdict()
}

object Nao3Postprocessor {
    private val labels = listOf(NaoLabel.N, NaoLabel.A, NaoLabel.O)

    fun fromLogits(logits: FloatArray, policy: Nao3DecisionPolicy): Nao3Verdict {
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
        var secondBest = Float.NEGATIVE_INFINITY
        for (index in probabilities.indices) {
            if (index == bestIndex) continue
            if (probabilities[index] > secondBest) secondBest = probabilities[index]
        }
        val bestProbability = probabilities[bestIndex]
        if (bestProbability <= secondBest) return Nao3Verdict.Indeterminate
        if (bestProbability - secondBest < policy.minMargin) return Nao3Verdict.Indeterminate

        val label = labels[bestIndex]
        val classPolicy = policy.classes[label] ?: return Nao3Verdict.Indeterminate
        if (classPolicy.alwaysAbstain) return Nao3Verdict.Indeterminate
        val minProbability = classPolicy.minProbability ?: return Nao3Verdict.Indeterminate
        if (bestProbability < minProbability) return Nao3Verdict.Indeterminate

        return Nao3Verdict.Classified(
            NaoDecision(
                label = label,
                confidence = bestProbability,
                pNormal = probabilities[0],
                pAf = probabilities[1],
                pOther = probabilities[2],
                topFindings = emptyList(),
            ),
        )
    }
}

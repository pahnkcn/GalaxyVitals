package app.healthtrack.data.protocol

import java.util.Locale

enum class NaoLabel {
    N,
    A,
    O,
}

data class NaoDecision(
    val label: NaoLabel,
    val confidence: Float,
    val pNormal: Float,
    val pAf: Float,
    val pOther: Float,
    val topFindings: List<LabeledScore>,
)

data class LabeledScore(
    val name: String,
    val score: Float,
)

object EcgFounderLabels {
    private const val MAX_TOP_FINDINGS = 20

    val ALL: List<String> = listOf(
        "ABNORMAL ECG",
        "NORMAL SINUS RHYTHM",
        "NORMAL ECG",
        "SINUS RHYTHM",
        "SINUS BRADYCARDIA",
        "ATRIAL FIBRILLATION",
        "SINUS TACHYCARDIA",
        "otherwise normal ecg",
        "LEFT AXIS DEVIATION",
        "PREMATURE VENTRICULAR COMPLEXES",
        "BORDERLINE ECG",
        "RIGHT BUNDLE BRANCH BLOCK",
        "SEPTAL INFARCT",
        "LEFT ATRIAL ENLARGEMENT",
        "NONSPECIFIC T WAVE ABNORMALITY",
        "LOW VOLTAGE QRS",
        "PREMATURE ATRIAL COMPLEXES",
        "ANTERIOR INFARCT",
        "INCOMPLETE RIGHT BUNDLE BRANCH BLOCK",
        "PREMATURE SUPRAVENTRICULAR COMPLEXES",
        "LEFT BUNDLE BRANCH BLOCK",
        "NONSPECIFIC T WAVE ABNORMALITY NOW EVIDENT IN",
        "NONSPECIFIC T WAVE ABNORMALITY NO LONGER EVIDENT IN",
        "T WAVE INVERSION NOW EVIDENT IN",
        "LATERAL INFARCT",
        "NONSPECIFIC ST ABNORMALITY",
        "LEFT VENTRICULAR HYPERTROPHY",
        "T WAVE INVERSION NO LONGER EVIDENT IN",
        "WITH RAPID VENTRICULAR RESPONSE",
        "QT HAS SHORTENED",
        "QT HAS LENGTHENED",
        "FUSION COMPLEXES",
        "ATRIAL FLUTTER",
        "MARKED SINUS BRADYCARDIA",
        "WITH SINUS ARRHYTHMIA",
        "NONSPECIFIC ST AND T WAVE ABNORMALITY",
        "LEFT ANTERIOR FASCICULAR BLOCK",
        "RIGHT AXIS DEVIATION",
        "ECTOPIC ATRIAL RHYTHM",
        "UNDETERMINED RHYTHM",
        "ANTEROSEPTAL INFARCT",
        "RIGHTWARD AXIS",
        "ST NOW DEPRESSED IN",
        "WITH SHORT PR",
        "WITH MARKED SINUS ARRHYTHMIA",
        "ST NO LONGER DEPRESSED IN",
        "INVERTED T WAVES HAVE REPLACED NONSPECIFIC T WAVE ABNORMALITY IN",
        "NON-SPECIFIC CHANGE IN ST SEGMENT IN",
        "NONSPECIFIC T WAVE ABNORMALITY HAS REPLACED INVERTED T WAVES IN",
        "JUNCTIONAL RHYTHM",
        "ELECTRONIC ATRIAL PACEMAKER",
        "ABERRANT CONDUCTION",
        "ELECTRONIC VENTRICULAR PACEMAKER",
        "T WAVE INVERSION LESS EVIDENT IN",
        "ANTEROLATERAL INFARCT",
        "WITH REPOLARIZATION ABNORMALITY",
        "RSR' OR QR PATTERN IN V1 SUGGESTS RIGHT VENTRICULAR CONDUCTION DELAY",
        "T WAVE INVERSION MORE EVIDENT IN",
        "WIDE QRS RHYTHM",
        "WITH PREMATURE VENTRICULAR OR ABERRANTLY CONDUCTED COMPLEXES",
        "RIGHT ATRIAL ENLARGEMENT",
        "INFERIOR INFARCT",
        "INCOMPLETE LEFT BUNDLE BRANCH BLOCK",
        "VOLTAGE CRITERIA FOR LEFT VENTRICULAR HYPERTROPHY",
        "OR DIGITALIS EFFECT",
        "BIFASCICULAR BLOCK",
        "ST NO LONGER ELEVATED IN",
        "WITH SLOW VENTRICULAR RESPONSE",
        "ST ELEVATION NOW PRESENT IN",
        "PREMATURE ECTOPIC COMPLEXES",
        "LEFT POSTERIOR FASCICULAR BLOCK",
        "T WAVE AMPLITUDE HAS DECREASED IN",
        "WITH A COMPETING JUNCTIONAL PACEMAKER",
        "RIGHT SUPERIOR AXIS DEVIATION",
        "BIATRIAL ENLARGEMENT",
        "VENTRICULAR-PACED RHYTHM",
        "ATRIAL-PACED RHYTHM",
        "T WAVE AMPLITUDE HAS INCREASED IN",
        "WITH QRS WIDENING",
        "WITH 1ST DEGREE AV BLOCK",
        "PROLONGED QT",
        "WITH PROLONGED AV CONDUCTION",
        "RIGHT VENTRICULAR HYPERTROPHY",
        "WITH QRS WIDENING AND REPOLARIZATION ABNORMALITY",
        "ATRIAL-SENSED VENTRICULAR-PACED RHYTHM",
        "AV SEQUENTIAL OR DUAL CHAMBER ELECTRONIC PACEMAKER",
        "PULMONARY DISEASE PATTERN",
        "ACUTE MI / STEMI",
        "INFERIOR-POSTERIOR INFARCT",
        "NONSPECIFIC INTRAVENTRICULAR CONDUCTION DELAY",
        "PREMATURE VENTRICULAR AND FUSION COMPLEXES",
        "IN A PATTERN OF BIGEMINY",
        "AV DUAL-PACED RHYTHM",
        "SUPRAVENTRICULAR TACHYCARDIA",
        "VENTRICULAR-PACED COMPLEXES",
        "WIDE QRS TACHYCARDIA",
        "RSR' PATTERN IN V1",
        "ST LESS DEPRESSED IN",
        "VENTRICULAR TACHYCARDIA",
        "EARLY REPOLARIZATION",
        "ST MORE DEPRESSED IN",
        "ANTEROLATERAL LEADS",
        "ELECTRONIC DEMAND PACING",
        "RBBB AND LEFT ANTERIOR FASCICULAR BLOCK",
        "LATERAL INJURY PATTERN",
        "BIVENTRICULAR PACEMAKER DETECTED",
        "SUSPECT UNSPECIFIED PACEMAKER FAILURE",
        "WOLFF-PARKINSON-WHITE",
        "WITH VENTRICULAR ESCAPE COMPLEXES",
        "INFERIOR INJURY PATTERN",
        "CONSIDER RIGHT VENTRICULAR INVOLVEMENT IN ACUTE INFERIOR INFARCT",
        "ST ELEVATION HAS REPLACED ST DEPRESSION IN",
        "NONSPECIFIC INTRAVENTRICULAR BLOCK",
        "MASKED BY FASCICULAR BLOCK",
        "PEDIATRIC ECG ANALYSIS",
        "BLOCKED",
        "WITH UNDETERMINED RHYTHM IRREGULARITY",
        "LEFTWARD AXIS",
        "WITH 2ND DEGREE SA BLOCK MOBITZ I",
        "ACUTE",
        "ABNORMAL LEFT AXIS DEVIATION",
        "WITH COMPLETE HEART BLOCK",
        "NO P-WAVES FOUND",
        "ST LESS ELEVATED IN",
        "WITH RETROGRADE CONDUCTION",
        "ST MORE ELEVATED IN",
        "JUNCTIONAL BRADYCARDIA",
        "WITH VARIABLE AV BLOCK",
        "ANTERIOR INJURY PATTERN",
        "WITH JUNCTIONAL ESCAPE COMPLEXES",
        "ACUTE MI",
        "ACUTE PERICARDITIS",
        "POSTERIOR INFARCT",
        "IDIOVENTRICULAR RHYTHM",
        "WITH 2ND DEGREE SA BLOCK MOBITZ II",
        "R IN AVL",
        "SINUS/ATRIAL CAPTURE",
        "AV DUAL-PACED COMPLEXES",
        "INFEROLATERAL INJURY PATTERN",
        "RBBB AND LEFT POSTERIOR FASCICULAR BLOCK",
        "ANTEROLATERAL INJURY PATTERN",
        "ATRIAL-PACED COMPLEXES",
        "WITH SINUS PAUSE",
        "BIVENTRICULAR HYPERTROPHY",
        "ABNORMAL RIGHT AXIS DEVIATION",
        "SUPRAVENTRICULAR COMPLEXES",
        "WITH 2ND DEGREE AV BLOCK MOBITZ I",
        "WITH 2:1 AV CONDUCTION",
        "WITH AV DISSOCIATION",
        "MULTIFOCAL ATRIAL TACHYCARDIA",
    )

    private val AF = setOf(
        "ATRIAL FIBRILLATION",
        "ATRIAL FLUTTER",
        "WITH RAPID VENTRICULAR RESPONSE",
        "WITH SLOW VENTRICULAR RESPONSE",
        "NO P-WAVES FOUND",
        "MULTIFOCAL ATRIAL TACHYCARDIA",
    )

    private val NORMAL = setOf(
        "NORMAL SINUS RHYTHM",
        "NORMAL ECG",
        "SINUS RHYTHM",
        "SINUS BRADYCARDIA",
        "SINUS TACHYCARDIA",
        "otherwise normal ecg",
        "MARKED SINUS BRADYCARDIA",
        "WITH SINUS ARRHYTHMIA",
        "WITH MARKED SINUS ARRHYTHMIA",
    )

    private val GENERIC = setOf("ABNORMAL ECG", "BORDERLINE ECG")

    fun decide(probs: FloatArray, topK: Int = 5): NaoDecision {
        require(probs.size == ALL.size) { "expected ${ALL.size} scores, got ${probs.size}" }
        require(probs.all { it.isFinite() && it in 0f..1f }) {
            "scores must be finite probabilities"
        }
        var pAf = 0f
        var pN = 0f
        var pO = 0f
        val ranked = ArrayList<LabeledScore>(ALL.size)
        for (i in ALL.indices) {
            val name = ALL[i]
            val p = probs[i]
            ranked += LabeledScore(name, p)
            when {
                name in AF -> pAf = maxOf(pAf, p)
                name in NORMAL -> pN = maxOf(pN, p)
                name in GENERIC -> Unit
                else -> pO = maxOf(pO, p)
            }
        }
        ranked.sortByDescending { it.score }
        val top = ranked.take(topK.coerceIn(0, MAX_TOP_FINDINGS))
        val label = when {
            pAf >= 0.45f && pAf >= pN -> NaoLabel.A
            pN >= 0.40f && pN >= pAf && pN >= pO * 0.85f -> NaoLabel.N
            else -> NaoLabel.O
        }
        val confidence = when (label) {
            NaoLabel.A -> pAf
            NaoLabel.N -> pN
            NaoLabel.O -> maxOf(pO, 1f - maxOf(pAf, pN))
        }.coerceIn(0f, 1f)
        return NaoDecision(label, confidence, pN, pAf, pO, top)
    }

    /**
     * Linear probe trained on frozen ECGFounder 150-d outputs.
     * [coef] is 3 x 150 for labels N, A, O.
     */
    fun decideLogistic(
        probs: FloatArray,
        coef: Array<FloatArray>,
        intercept: FloatArray,
        topK: Int = 5,
    ): NaoDecision {
        require(probs.size == ALL.size)
        require(coef.size == 3 && intercept.size == 3)
        require(probs.all { it.isFinite() && it in 0f..1f })
        require(intercept.all { it.isFinite() })
        val logits = FloatArray(3)
        for (k in 0..2) {
            var s = intercept[k]
            val row = coef[k]
            require(row.size == probs.size)
            require(row.all { it.isFinite() })
            for (i in probs.indices) s += row[i] * probs[i]
            require(s.isFinite()) { "calibrator produced a non-finite logit" }
            logits[k] = s
        }
        val maxLogit = logits.max()
        var sum = 0f
        val soft = FloatArray(3)
        for (k in 0..2) {
            soft[k] = kotlin.math.exp((logits[k] - maxLogit).toDouble()).toFloat()
            sum += soft[k]
        }
        require(sum.isFinite() && sum > 0f) { "calibrator produced invalid probabilities" }
        for (k in 0..2) soft[k] /= sum
        val best = soft.indices.maxBy { soft[it] }
        val ranked = ALL.indices
            .map { LabeledScore(ALL[it], probs[it]) }
            .sortedByDescending { it.score }
            .take(topK.coerceIn(0, MAX_TOP_FINDINGS))
        return NaoDecision(
            label = NaoLabel.entries[best],
            confidence = soft[best],
            pNormal = soft[0],
            pAf = soft[1],
            pOther = soft[2],
            topFindings = ranked,
        )
    }

    fun encodeFindings(items: List<LabeledScore>): String =
        items.asSequence()
            .filter { it.score.isFinite() }
            .take(MAX_TOP_FINDINGS)
            .joinToString("|") { "${it.name}:${String.format(Locale.US, "%.3f", it.score)}" }

    fun decodeFindings(raw: String): List<LabeledScore> {
        if (raw.isBlank()) return emptyList()
        return raw.splitToSequence('|')
            .mapNotNull { token ->
                val idx = token.lastIndexOf(':')
                if (idx <= 0) return@mapNotNull null
                val score = token.substring(idx + 1).toFloatOrNull()
                    ?.takeIf(Float::isFinite)
                    ?: return@mapNotNull null
                LabeledScore(token.substring(0, idx), score)
            }
            .take(MAX_TOP_FINDINGS)
            .toList()
    }
}

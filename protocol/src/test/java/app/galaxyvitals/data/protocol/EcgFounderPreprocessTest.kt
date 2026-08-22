package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.EcgSample
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.Assert.assertThrows
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class EcgFounderPreprocessTest {

    @Test
    fun windowsFrom500HzAreTenSeconds() {
        val samples = synthetic(seconds = 30, srHz = 500, hz = 1.2)
        val windows = EcgFounderPreprocess.windows(samples, 500)
        assertThat(windows.size).isAtLeast(3)
        windows.forEach { w ->
            assertThat(w.samples.size).isEqualTo(EcgFounderPreprocess.WINDOW_SAMPLES)
            val mean = w.samples.average()
            assertThat(abs(mean)).isLessThan(1e-3)
        }
    }

    @Test
    fun shortTraceIsRejectedInsteadOfZeroPadded() {
        val samples = synthetic(seconds = 8, srHz = 500, hz = 1.0)
        val windows = EcgFounderPreprocess.windows(samples, 500)
        assertThat(windows).isEmpty()
    }

    @Test
    fun resamples250HzUpTo500() {
        val samples = synthetic(seconds = 10, srHz = 250, hz = 1.5)
        val out = EcgFounderPreprocess.resampleToTarget(samples, 250)
        assertThat(out.size).isAtLeast(4900)
        assertThat(out.size).isAtMost(5100)
    }

    @Test
    fun qualityRejectsFlatLine() {
        val samples = List(5000) { i -> EcgSample(i * 2L, 0f, 70) }
        val windows = EcgFounderPreprocess.windows(samples, 500)
        val q = EcgFounderPreprocess.quality(windows, usablePct = 0.0)
        assertThat(q.usable).isFalse()
    }

    @Test
    fun qualityRejectsConstantOffsetEvenWhenAllRowsAreMarkedUsable() {
        val samples = List(5_000) { i -> EcgSample(i * 2L, 1f, 70) }

        val quality = EcgFounderPreprocess.quality(
            EcgFounderPreprocess.windows(samples, 500),
            usablePct = 100.0,
        )

        assertThat(quality.usable).isFalse()
    }

    @Test
    fun mapsAfAndSinusToNao() {
        val probs = FloatArray(EcgFounderLabels.ALL.size)
        val af = EcgFounderLabels.ALL.indexOf("ATRIAL FIBRILLATION")
        val nsr = EcgFounderLabels.ALL.indexOf("NORMAL SINUS RHYTHM")
        probs[nsr] = 0.91f
        assertThat(EcgFounderLabels.decide(probs).label).isEqualTo(NaoLabel.N)
        probs[nsr] = 0.20f
        probs[af] = 0.88f
        assertThat(EcgFounderLabels.decide(probs).label).isEqualTo(NaoLabel.A)
        probs[af] = 0.10f
        probs[EcgFounderLabels.ALL.indexOf("PREMATURE VENTRICULAR COMPLEXES")] = 0.77f
        assertThat(EcgFounderLabels.decide(probs).label).isEqualTo(NaoLabel.O)
    }

    @Test
    fun logisticHeadPrefersAfWhenWeightIsOnAf() {
        val probs = FloatArray(EcgFounderLabels.ALL.size)
        val af = EcgFounderLabels.ALL.indexOf("ATRIAL FIBRILLATION")
        probs[af] = 0.9f
        val coef = Array(3) { FloatArray(EcgFounderLabels.ALL.size) }
        coef[1][af] = 8f
        val intercept = floatArrayOf(0f, 0f, 0f)
        val decided = EcgFounderLabels.decideLogistic(probs, coef, intercept)
        assertThat(decided.label).isEqualTo(NaoLabel.A)
        assertThat(decided.pAf).isGreaterThan(decided.pNormal)
    }

    @Test
    fun findingsRoundTrip() {
        val items = listOf(LabeledScore("ATRIAL FIBRILLATION", 0.812f))
        val encoded = EcgFounderLabels.encodeFindings(items)
        val decoded = EcgFounderLabels.decodeFindings(encoded)
        assertThat(decoded).hasSize(1)
        assertThat(decoded[0].name).isEqualTo("ATRIAL FIBRILLATION")
        assertThat(decoded[0].score).isWithin(0.001f).of(0.812f)
    }

    @Test
    fun qualityRejectsNonFiniteValues() {
        val nanWindow = PreparedWindow(floatArrayOf(0.1f, Float.NaN), 0L, Float.NaN)

        assertThat(EcgFounderPreprocess.quality(listOf(nanWindow), usablePct = 90.0).usable)
            .isFalse()
        assertThat(
            EcgFounderPreprocess.quality(
                listOf(PreparedWindow(floatArrayOf(0.1f), 0L, 0.1f)),
                usablePct = Double.NaN,
            ).usable,
        ).isFalse()
    }

    @Test
    fun findingsSerializationIsLocaleInvariant() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val encoded = EcgFounderLabels.encodeFindings(
                listOf(LabeledScore("ATRIAL FIBRILLATION", 0.812f)),
            )
            val decoded = EcgFounderLabels.decodeFindings(encoded)

            assertThat(encoded).contains(":0.812")
            assertThat(decoded).hasSize(1)
            assertThat(decoded.single().score).isWithin(0.001f).of(0.812f)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun findingsSerializationDropsNonFiniteScores() {
        val encoded = EcgFounderLabels.encodeFindings(
            listOf(
                LabeledScore("NaN", Float.NaN),
                LabeledScore("positive infinity", Float.POSITIVE_INFINITY),
                LabeledScore("normal", 0.5f),
            ),
        )
        val decoded = EcgFounderLabels.decodeFindings(
            "$encoded|negative infinity:-Infinity|not-a-number:NaN",
        )

        assertThat(encoded).isEqualTo("normal:0.500")
        assertThat(decoded).containsExactly(LabeledScore("normal", 0.5f))
    }

    @Test
    fun decisionsRejectNonFiniteOrOutOfRangeProbabilities() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f, 1.1f).forEach { invalid ->
            val probabilities = FloatArray(EcgFounderLabels.ALL.size)
            probabilities[0] = invalid

            assertThrows(IllegalArgumentException::class.java) {
                EcgFounderLabels.decide(probabilities)
            }
        }
    }

    private fun FloatArray.average(): Double = sum().toDouble() / size

    private fun synthetic(seconds: Int, srHz: Int, hz: Double): List<EcgSample> {
        val n = seconds * srHz
        return List(n) { i ->
            val t = i.toDouble() / srHz
            EcgSample(
                relMs = i * 1000L / srHz,
                valueMv = (0.8 * sin(2 * PI * hz * t)).toFloat(),
                hrBpm = 70,
            )
        }
    }
}

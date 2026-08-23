package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.CaptureSource
import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.domain.TimingTrust
import app.galaxyvitals.domain.Wrist
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class Nao3PreprocessTest {

    @Test
    fun linearResampleUsesEndpointDurationAt256Hz() {
        val output = Nao3Preprocess.linearResample(
            input = floatArrayOf(0f, 1f, 2f),
            sourceHz = 500,
        )

        assertThat(output).hasLength(2)
        assertThat(output[0]).isEqualTo(0f)
        assertThat(output[1]).isWithin(1e-6f).of(1.953125f)
    }

    @Test
    fun forwardReverseSosMatchesFiveSectionZeroStateGolden() {
        val impulse = FloatArray(16).also { it[0] = 1f }
        val expected = floatArrayOf(
            0.294407427f,
            0.240359500f,
            0.116894685f,
            0.000943755207f,
            -0.0573260151f,
            -0.0576036014f,
            -0.0305668507f,
            -0.00863565877f,
            -0.00584917190f,
            -0.0159302671f,
            -0.0254593268f,
            -0.0269578416f,
            -0.0211505964f,
            -0.0122857420f,
            -0.00468524219f,
            -0.000850590295f,
        )

        val output = Nao3Preprocess.forwardReverseSos(impulse)

        output.indices.forEach { index ->
            assertThat(output[index]).isWithin(1e-6f).of(expected[index])
        }
    }

    @Test
    fun thirtySecondsAt500HzProducesExactFiniteModelInput() {
        val samples = synthetic(seconds = 30)
        val resampled = Nao3Preprocess.linearResample(
            FloatArray(samples.size) { samples[it].valueMv },
            sourceHz = 500,
        )
        val output = Nao3Preprocess.prepare(parsed(samples))

        assertThat(resampled).hasLength(Nao3Preprocess.INPUT_SAMPLES)
        assertThat(output).hasLength(Nao3Preprocess.INPUT_SAMPLES)
        assertThat(output.all(Float::isFinite)).isTrue()
        assertThat(abs(output.average())).isLessThan(1e-4)
        assertThat(standardDeviation(output)).isWithin(1e-3).of(1.0)
    }

    @Test
    fun constantInputProducesFiniteModelValues() {
        val samples = List(15_000) { index ->
            EcgSample(index * 2L, 1.75f, 70, index)
        }

        val output = Nao3Preprocess.prepare(parsed(samples))

        assertThat(output).hasLength(Nao3Preprocess.INPUT_SAMPLES)
        assertThat(output.all(Float::isFinite)).isTrue()
    }

    @Test
    fun shorterRecordingIsCenterZeroPaddedAfterFilteringAndZScore() {
        val source = synthetic(seconds = 10)

        val output = Nao3Preprocess.prepare(parsed(source))

        val resampledLength = Nao3Preprocess.linearResample(
            FloatArray(source.size) { source[it].valueMv },
            sourceHz = 500,
        ).size
        val padLeft = (Nao3Preprocess.INPUT_SAMPLES - resampledLength) / 2
        assertThat(output.copyOfRange(0, padLeft).all { it == 0f }).isTrue()
        assertThat(output.copyOfRange(padLeft, padLeft + resampledLength).any { abs(it) > 1e-3f })
            .isTrue()
        assertThat(output.copyOfRange(padLeft + resampledLength, output.size).all { it == 0f })
            .isTrue()
    }

    @Test
    fun normalizedRightWristMatchesLeftReferenceInsteadOfBeingInvertedTwice() {
        val samples = synthetic(seconds = 30)
        val left = Nao3Preprocess.prepare(
            parsed(samples, signFactor = 1, polarityNormalized = false),
        )
        val normalizedRight = Nao3Preprocess.prepare(
            parsed(samples, signFactor = -1, polarityNormalized = true),
        )
        val rawRight = Nao3Preprocess.prepare(
            parsed(samples, signFactor = -1, polarityNormalized = false),
        )

        assertThat(maxAbsoluteDifference(left, normalizedRight)).isLessThan(1e-6f)
        assertThat(maxAbsoluteSum(left, rawRight)).isLessThan(1e-5f)
    }

    private fun synthetic(seconds: Int, srHz: Int = 500): List<EcgSample> =
        List(seconds * srHz) { index ->
            val timeSec = index.toDouble() / srHz
            val value =
                0.65 * sin(2 * PI * 1.2 * timeSec) +
                    0.12 * sin(2 * PI * 3.7 * timeSec)
            EcgSample(index * 1000L / srHz, value.toFloat(), 70, index)
        }

    private fun parsed(
        samples: List<EcgSample>,
        signFactor: Int = 1,
        polarityNormalized: Boolean = false,
    ): ParsedEcgFile = EcgCsvParser.summarize(
        sessionId = "nao3-test",
        srHz = 500,
        unit = "mV",
        tsStartMs = 1L,
        wrist = if (signFactor < 0) Wrist.RIGHT else Wrist.LEFT,
        signFactor = signFactor,
        polarityNormalized = polarityNormalized,
        watchInfo = "test",
        samples = samples,
        schemaVersion = 2,
        captureSource = CaptureSource.HARDWARE,
        timingTrust = TimingTrust.SENSOR,
    )

    private fun FloatArray.average(): Double = fold(0.0) { sum, value -> sum + value } / size

    private fun standardDeviation(values: FloatArray): Double {
        val mean = values.average()
        return kotlin.math.sqrt(
            values.fold(0.0) { sum, value ->
                val centered = value - mean
                sum + centered * centered
            } / values.size,
        )
    }

    private fun maxAbsoluteDifference(first: FloatArray, second: FloatArray): Float =
        first.indices.maxOf { abs(first[it] - second[it]) }

    private fun maxAbsoluteSum(first: FloatArray, second: FloatArray): Float =
        first.indices.maxOf { abs(first[it] + second[it]) }
}

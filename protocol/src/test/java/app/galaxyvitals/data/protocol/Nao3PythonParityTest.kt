package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.CaptureSource
import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.domain.TimingTrust
import app.galaxyvitals.domain.Wrist
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Cross-language parity: this Kotlin inference path must agree with the Python
 * training path in ECGTraining (`src/ecgnao/preprocess_gv.py`).
 *
 * Why this exists: the training repo previously resampled with an anti-aliased
 * polyphase filter, applied the SOS cascade causally in a single pass, and
 * zero-padded short records. This file applies a zero-phase forward-reverse
 * cascade to an exact 30 s window with no padding. Those produce different
 * waveforms, so the model was trained on something it never sees on device.
 * This test fails if the two paths ever drift apart again.
 *
 * Fixtures come from `ECGTraining/scripts/build_parity_fixtures.py`. Inputs are
 * regenerated here by the same formulas rather than stored, and outputs are
 * compared as fingerprints: the cascade is IIR, so any coefficient, ordering or
 * state-handling difference propagates across the whole window.
 */
class Nao3PythonParityTest {

    private data class Fixture(
        val name: String,
        val sampleRateHz: Int,
        val inputSamples: Int,
        val mean: Double,
        val std: Double,
        val min: Double,
        val max: Double,
        val indices: List<Int>,
        val values: List<Double>,
    )

    @Test
    fun pythonAndKotlinPreprocessingAgreeOnEveryFixture() {
        val (tolerance, fixtures) = loadFixtures()
        assertThat(fixtures).isNotEmpty()

        fixtures.forEach { fixture ->
            val samples = generate(fixture.name, fixture.sampleRateHz)
            assertThat(samples).hasLength(fixture.inputSamples)

            val output = Nao3Preprocess.prepareExact(parsed(samples, fixture.sampleRateHz))
            assertThat(output).hasLength(Nao3Preprocess.INPUT_SAMPLES)

            val label = "${fixture.name}@${fixture.sampleRateHz}Hz"
            assertWithin(label, "mean", mean(output), fixture.mean, tolerance)
            assertWithin(label, "std", standardDeviation(output), fixture.std, tolerance)
            assertWithin(label, "min", output.min().toDouble(), fixture.min, tolerance)
            assertWithin(label, "max", output.max().toDouble(), fixture.max, tolerance)

            fixture.indices.forEachIndexed { position, sampleIndex ->
                assertWithin(
                    label,
                    "sample[$sampleIndex]",
                    output[sampleIndex].toDouble(),
                    fixture.values[position],
                    tolerance,
                )
            }
        }
    }

    @Test
    fun fixturesCoverBothSupportedSourceRatesAndTheHardSignals() {
        val (_, fixtures) = loadFixtures()
        assertThat(fixtures.map { it.sampleRateHz }.toSet()).containsAtLeast(500, 300)
        assertThat(fixtures.map { it.name }.toSet()).containsAtLeast(
            "qrs_train",        // transient the band-pass must preserve
            "mains_and_wander", // both notches plus sub-corner wander
            "near_constant",    // variance floor
        )
    }

    private fun assertWithin(
        fixture: String,
        field: String,
        actual: Double,
        expected: Double,
        tolerance: Double,
    ) {
        val delta = abs(actual - expected)
        assertWithMessage("$fixture $field delta=$delta (kotlin=$actual python=$expected)")
            .that(delta <= tolerance)
            .isTrue()
    }

    /** Restates `build_parity_fixtures.py::generate` exactly. */
    private fun generate(name: String, srHz: Int): FloatArray {
        val n = srHz * Nao3Preprocess.DURATION_SECONDS
        val x = DoubleArray(n)
        when (name) {
            "sinusoid" -> for (i in 0 until n) {
                val t = i.toDouble() / srHz
                x[i] = 0.65 * sin(2 * PI * 1.2 * t) + 0.12 * sin(2 * PI * 3.7 * t)
            }
            "qrs_train" -> {
                for (i in 0 until n) x[i] = 0.10 * sin(2 * PI * 0.15 * (i.toDouble() / srHz))
                val spike = doubleArrayOf(0.1, 0.5, 1.2, -0.4, 0.05, 0.02)
                var start = 0
                while (start < n - 6) {
                    for (k in spike.indices) x[start + k] += spike[k]
                    start += srHz
                }
            }
            "mains_and_wander" -> for (i in 0 until n) {
                val t = i.toDouble() / srHz
                x[i] = 0.6 * sin(2 * PI * 1.1 * t) +
                    0.40 * sin(2 * PI * 0.20 * t) +
                    0.25 * sin(2 * PI * 50.0 * t) +
                    0.20 * sin(2 * PI * 60.0 * t)
            }
            "dc_offset" -> for (i in 0 until n) {
                x[i] = 12.5 + 0.3 * sin(2 * PI * 1.4 * (i.toDouble() / srHz))
            }
            "near_constant" -> for (i in 0 until n) {
                x[i] = 1.75 + 1e-7 * sin(2 * PI * 2.0 * (i.toDouble() / srHz))
            }
            else -> throw IllegalArgumentException("unknown fixture $name")
        }
        return FloatArray(n) { x[it].toFloat() }
    }

    private fun parsed(values: FloatArray, srHz: Int): ParsedEcgFile {
        val samples = List(values.size) { index ->
            EcgSample(index * 1000L / srHz, values[index], 70, index)
        }
        return EcgCsvParser.summarize(
            sessionId = "nao3-parity",
            srHz = srHz,
            unit = "mV",
            tsStartMs = 1L,
            wrist = Wrist.LEFT,
            signFactor = 1,
            polarityNormalized = false,
            watchInfo = "test",
            samples = samples,
            schemaVersion = 2,
            captureSource = CaptureSource.HARDWARE,
            timingTrust = TimingTrust.SENSOR,
        )
    }

    private fun loadFixtures(): Pair<Double, List<Fixture>> {
        val text = checkNotNull(
            javaClass.getResourceAsStream("/$FIXTURE_RESOURCE")
        ) { "missing test resource $FIXTURE_RESOURCE; regenerate with ECGTraining/scripts/build_parity_fixtures.py" }
            .bufferedReader()
            .readText()

        var tolerance = 1e-5
        val fixtures = mutableListOf<Fixture>()
        var name = ""
        var srHz = 0
        var inputSamples = 0
        var stats = listOf<Double>()
        var indices = listOf<Int>()

        text.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val parts = line.split(" ")
                when (parts[0]) {
                    "tolerance" -> tolerance = parts[1].toDouble()
                    "fixture" -> {
                        name = parts[1]; srHz = parts[2].toInt(); inputSamples = parts[3].toInt()
                    }
                    "stats" -> stats = parts.drop(1).map(String::toDouble)
                    "indices" -> indices = parts.drop(1).map(String::toInt)
                    "values" -> fixtures += Fixture(
                        name = name,
                        sampleRateHz = srHz,
                        inputSamples = inputSamples,
                        mean = stats[0], std = stats[1], min = stats[2], max = stats[3],
                        indices = indices,
                        values = parts.drop(1).map(String::toDouble),
                    )
                }
            }
        return tolerance to fixtures
    }

    private fun mean(values: FloatArray): Double =
        values.fold(0.0) { sum, value -> sum + value } / values.size

    private fun standardDeviation(values: FloatArray): Double {
        val average = mean(values)
        return kotlin.math.sqrt(
            values.fold(0.0) { sum, value ->
                val centered = value - average
                sum + centered * centered
            } / values.size,
        )
    }

    private companion object {
        const val FIXTURE_RESOURCE = "nao3_parity_fixtures.txt"
    }
}

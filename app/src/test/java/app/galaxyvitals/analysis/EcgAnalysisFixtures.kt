package app.galaxyvitals.analysis

import app.galaxyvitals.data.protocol.ParsedEcgFile
import app.galaxyvitals.domain.CaptureSource
import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.domain.TimingTrust
import app.galaxyvitals.domain.Wrist
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

object EcgAnalysisFixtures {
    fun clean72BpmRecording(): ParsedEcgFile =
        parsedRecording(syntheticQrs(seconds = 30.0, bpm = 72.0).toSamples())

    fun shortClean20sRecording(): ParsedEcgFile =
        parsedRecording(syntheticQrs(seconds = 20.0, bpm = 72.0).toSamples())

    fun contaminated30sRecording(): ParsedEcgFile {
        val samples = syntheticQrs(seconds = 30.0, bpm = 72.0).toSamples().toMutableList()
        val srHz = 500
        val start = (13.0 * srHz).toInt()
        val end = (14.2 * srHz).toInt()
        for (index in start until end) {
            samples[index] = samples[index].copy(valueMv = 0.02f)
        }
        return parsedRecording(samples)
    }

    fun fortySecondRecordingWithDirtyPrefix(): ParsedEcgFile {
        val samples = syntheticQrs(seconds = 40.0, bpm = 72.0).toSamples().toMutableList()
        val srHz = 500
        for (index in 0 until 3 * srHz) {
            samples[index] = samples[index].copy(valueMv = 0.02f)
        }
        return parsedRecording(samples)
    }

    fun lowQualityRecording(): ParsedEcgFile {
        val samples = List(1_000) { index ->
            EcgSample(
                relMs = index * 2L,
                valueMv = 0.02f,
                hrBpm = null,
                sampleIndex = index,
            )
        }
        return parsedRecording(samples)
    }

    private fun syntheticQrs(
        seconds: Double,
        bpm: Double,
        srHz: Int = 500,
        invert: Boolean = false,
        dcOffsetMv: Double = 0.0,
        tWaveMv: Double = 0.30,
        missedBeatIndex: Int? = null,
        artifactAtSec: Double? = null,
        artifactMv: Double = 6.0,
        noiseRms: Double = 0.0,
        driftMvPerSec: Double = 0.0,
        seed: Long = 1L,
    ): FloatArray {
        val n = (seconds * srHz).roundToInt()
        val out = DoubleArray(n)
        val period = srHz * 60.0 / bpm
        var beat = 0
        var peak = period * 0.5
        while (peak < n) {
            val r = peak.roundToInt()
            if (missedBeatIndex != beat) {
                addGaussian(out, r - (0.18 * srHz).roundToInt(), 0.12, 0.035 * srHz)
                addGaussian(out, r - (0.025 * srHz).roundToInt(), -0.15, 0.008 * srHz)
                addGaussian(out, r, 1.20, 0.010 * srHz)
                addGaussian(out, r + (0.025 * srHz).roundToInt(), -0.28, 0.010 * srHz)
                addGaussian(out, r + (0.22 * srHz).roundToInt(), tWaveMv, 0.045 * srHz)
            }
            beat++
            peak += period
        }
        val rng = java.util.Random(seed)
        for (index in out.indices) {
            val t = index.toDouble() / srHz
            out[index] += 0.04 * sin(2 * PI * 0.25 * t)
            out[index] += driftMvPerSec * t
            out[index] += dcOffsetMv
            if (noiseRms > 0.0) out[index] += rng.nextGaussian() * noiseRms
        }
        artifactAtSec?.let { sec ->
            val index = (sec * srHz).roundToInt()
            if (index in out.indices) out[index] = artifactMv
        }
        val sign = if (invert) -1.0 else 1.0
        return FloatArray(n) { (sign * out[it]).toFloat() }
    }

    private fun addGaussian(out: DoubleArray, center: Int, amplitude: Double, sigma: Double) {
        if (sigma <= 0.0) return
        val radius = (sigma * 4.0).roundToInt().coerceAtLeast(1)
        val twoSigmaSq = 2.0 * sigma * sigma
        for (offset in -radius..radius) {
            val index = center + offset
            if (index in out.indices) {
                out[index] += amplitude * exp(-(offset * offset) / twoSigmaSq)
            }
        }
    }

    private fun FloatArray.toSamples(): List<EcgSample> = indices.map { index ->
        EcgSample(relMs = index * 2L, valueMv = this[index], hrBpm = null, sampleIndex = index)
    }

    private fun parsedRecording(samples: List<EcgSample>): ParsedEcgFile {
        val usable = samples.count { abs(it.valueMv) > 1e-6f }
        val durationSec = if (samples.size <= 1) {
            0.0
        } else {
            (samples.last().relMs - samples.first().relMs) / 1000.0
        }
        return ParsedEcgFile(
            sessionId = "analysis-fixture",
            srHz = 500,
            unit = "mV",
            tsStartMs = 1L,
            wrist = Wrist.LEFT,
            signFactor = 1,
            polarityNormalized = false,
            watchInfo = "test",
            samples = samples,
            hrMedian = null,
            hrMin = null,
            hrMax = null,
            hrCoveragePct = 0.0,
            usablePct = if (samples.isEmpty()) 0.0 else usable * 100.0 / samples.size,
            durationSec = durationSec,
            schemaVersion = 2,
            captureSource = CaptureSource.HARDWARE,
            timingTrust = TimingTrust.SENSOR,
        )
    }
}

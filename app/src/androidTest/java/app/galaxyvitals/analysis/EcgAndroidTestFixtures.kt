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

internal object EcgAndroidTestFixtures {
    fun clean72BpmRecording(): ParsedEcgFile {
        val n = 15_000
        val srHz = 500
        val bpm = 72.0
        val out = DoubleArray(n)
        val period = srHz * 60.0 / bpm
        var peak = period * 0.5
        while (peak < n) {
            val r = peak.roundToInt()
            addGaussian(out, r - (0.18 * srHz).roundToInt(), 0.12, 0.035 * srHz)
            addGaussian(out, r - (0.025 * srHz).roundToInt(), -0.15, 0.008 * srHz)
            addGaussian(out, r, 1.20, 0.010 * srHz)
            addGaussian(out, r + (0.025 * srHz).roundToInt(), -0.28, 0.010 * srHz)
            addGaussian(out, r + (0.22 * srHz).roundToInt(), 0.30, 0.045 * srHz)
            peak += period
        }
        for (index in out.indices) {
            val t = index.toDouble() / srHz
            out[index] += 0.04 * sin(2 * PI * 0.25 * t)
        }
        val samples = List(n) { index ->
            EcgSample(
                relMs = index * 2L,
                valueMv = out[index].toFloat(),
                hrBpm = null,
                sampleIndex = index,
            )
        }
        val usable = samples.count { abs(it.valueMv) > 1e-6f }
        val durationSec = (samples.last().relMs - samples.first().relMs) / 1000.0
        return ParsedEcgFile(
            sessionId = "androidtest-fixture",
            srHz = srHz,
            unit = "mV",
            tsStartMs = 1L,
            wrist = Wrist.LEFT,
            signFactor = 1,
            polarityNormalized = false,
            watchInfo = "androidTest",
            samples = samples,
            hrMedian = null,
            hrMin = null,
            hrMax = null,
            hrCoveragePct = 0.0,
            usablePct = usable * 100.0 / samples.size,
            durationSec = durationSec,
            schemaVersion = 2,
            captureSource = CaptureSource.HARDWARE,
            timingTrust = TimingTrust.SENSOR,
        )
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
}

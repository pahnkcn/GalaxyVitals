package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.EcgSample
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min

/**
 * Builds a derived 0.5-40 Hz waveform for display without mutating raw ECG rows.
 *
 * Galaxy Watch `ECG_ON_DEMAND` samples ride on a ~100 mV electrode offset that
 * then polarizes for several seconds. A causal one-pole high-pass rings on that
 * transient and the phone autoscale turns the first seconds into a giant swing.
 * Zero-phase SOS (the same `bandpass_0.5_40` style GeminiMan uses) removes the
 * offset without a startup tail. Remaining in-band contact pops are a capture
 * problem and are kept out of the stored 30 s by sensor warmup.
 */
object EcgDisplayProcessor {
    private const val HIGH_PASS_HZ = 0.5
    private const val LOW_PASS_HZ = 40.0

    fun filter(
        samples: List<EcgSample>,
        srHz: Int,
        signFactor: Int,
        polarityNormalized: Boolean,
    ): List<EcgSample> {
        require(srHz > 0) { "ECG sample rate must be positive" }
        if (samples.isEmpty()) return ArrayList(0)

        val polarity = effectivePolarity(signFactor, polarityNormalized)
        val oriented = DoubleArray(samples.size) { samples[it].valueMv * polarity.toDouble() }
        val filtered = filtfilt(sosFor(srHz), oriented)
        return List(samples.size) { index ->
            samples[index].copy(valueMv = filtered[index].toFloat())
        }
    }

    /** SciPy `butter(4, [0.5, 40], btype='bandpass', output='sos')` at 250/300/500 Hz. */
    private fun sosFor(srHz: Int): Array<DoubleArray> = when (srHz) {
        250 -> SOS_250
        300 -> SOS_300
        500 -> SOS_500
        else -> firstOrderBandpassSos(srHz)
    }

    private fun firstOrderBandpassSos(srHz: Int): Array<DoubleArray> {
        val timeStep = 1.0 / srHz
        val highPassRc = 1.0 / (2.0 * PI * HIGH_PASS_HZ)
        val highPassAlpha = highPassRc / (timeStep + highPassRc)
        val lowPassRc = 1.0 / (2.0 * PI * LOW_PASS_HZ)
        val lowPassAlpha = timeStep / (lowPassRc + timeStep)
        return arrayOf(
            doubleArrayOf(highPassAlpha, -highPassAlpha, 0.0, 1.0, -highPassAlpha, 0.0),
            doubleArrayOf(lowPassAlpha, 0.0, 0.0, 1.0, -(1.0 - lowPassAlpha), 0.0),
        )
    }

    internal fun filtfilt(sos: Array<DoubleArray>, x: DoubleArray): DoubleArray {
        if (x.isEmpty()) return DoubleArray(0)
        val pad = min(x.size - 1, 3 * 500)
        val ext = oddExtend(x, pad)
        val fwd = sosFilt(sos, ext)
        val revIn = DoubleArray(fwd.size) { fwd[fwd.lastIndex - it] }
        val rev = sosFilt(sos, revIn)
        return DoubleArray(x.size) { index -> rev[rev.lastIndex - (pad + index)] }
    }

    private fun sosFilt(sos: Array<DoubleArray>, x: DoubleArray): DoubleArray {
        var y = x
        for (section in sos) {
            y = biquad(section, y)
        }
        return y
    }

    private fun biquad(s: DoubleArray, x: DoubleArray): DoubleArray {
        if (x.isEmpty()) return DoubleArray(0)
        val a0 = if (abs(s[3]) < 1e-12) 1.0 else s[3]
        val b0 = s[0] / a0
        val b1 = s[1] / a0
        val b2 = s[2] / a0
        val a1 = s[4] / a0
        val a2 = s[5] / a0
        val y = DoubleArray(x.size)
        val denominator = 1.0 + a1 + a2
        val dcGain = if (abs(denominator) > 1e-12) (b0 + b1 + b2) / denominator else 0.0
        var x1 = x[0]
        var x2 = x[0]
        var y1 = x[0] * dcGain
        var y2 = y1
        for (i in x.indices) {
            val xi = x[i]
            val yi = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            y[i] = yi
            x2 = x1
            x1 = xi
            y2 = y1
            y1 = yi
        }
        return y
    }

    private fun oddExtend(x: DoubleArray, pad: Int): DoubleArray {
        if (pad <= 0) return x.copyOf()
        val n = x.size
        val out = DoubleArray(n + 2 * pad)
        for (i in 0 until pad) {
            val idx = reflect(pad - i, n)
            out[i] = 2 * x[0] - x[idx]
        }
        System.arraycopy(x, 0, out, pad, n)
        for (i in 0 until pad) {
            val idx = reflect(n - 2 - i, n)
            out[pad + n + i] = 2 * x[n - 1] - x[idx]
        }
        return out
    }

    private fun reflect(i: Int, n: Int): Int {
        if (n <= 1) return 0
        var x = i
        val period = 2 * (n - 1)
        x %= period
        if (x < 0) x += period
        return if (x >= n) period - x else x
    }

    private val SOS_500 = arrayOf(
        doubleArrayOf(
            0.0021387987326912015, 0.004277597465382403, 0.0021387987326912015,
            1.0, -1.22787587909828, 0.39352306024517103,
        ),
        doubleArrayOf(1.0, 2.0, 1.0, 1.0, -1.486663673168146, 0.6949675580253452),
        doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.9882154714982394, 0.9882564156624591),
        doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.995246749028118, 0.9952864068099667),
    )

    private val SOS_300 = arrayOf(
        doubleArrayOf(
            0.012121844325570758, 0.024243688651141515, 0.012121844325570758,
            1.0, -0.812318281836406, 0.19421398887402397,
        ),
        doubleArrayOf(1.0, 2.0, 1.0, 1.0, -1.0499649802548534, 0.564870794560166),
        doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.980403853267054, 0.9805169549296242),
        doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.9920410037991123, 0.9921509621795904),
    )

    private val SOS_250 = arrayOf(
        doubleArrayOf(
            0.02196126343374193, 0.04392252686748386, 0.02196126343374193,
            1.0, -0.6215552807265685, 0.1305843533436152,
        ),
        doubleArrayOf(1.0, 2.0, 1.0, 1.0, -0.8176588707461863, 0.5194597165945805),
        doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.976514489017057, 0.9766768504796793),
        doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.9904258738606502, 0.990584060251276),
    )
}

package app.galaxyvitals.data.protocol.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Second-order sections and zero-phase filtering.
 *
 * Nothing here knows about ECG. It is the arithmetic the chain is built from,
 * kept apart so the chain itself reads as a sequence of decisions rather than a
 * sequence of difference equations.
 */
internal data class Biquad(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
)

/** Butterworth section quality factors for an even [order]. */
internal fun butterworthQs(order: Int): DoubleArray {
    val sections = order / 2
    return DoubleArray(sections) { k -> 1.0 / (2.0 * cos((2.0 * (k + 1) - 1.0) * PI / (2.0 * order))) }
}

internal fun lowPassSections(cutoffHz: Double, srHz: Double, order: Int): List<Biquad> =
    butterworthQs(order).map { q -> lowPassBiquad(cutoffHz, srHz, q) }

/** RBJ cookbook biquads; stable and well conditioned at these cutoffs. */
internal fun lowPassBiquad(frequencyHz: Double, srHz: Double, q: Double): Biquad {
    val w0 = 2.0 * PI * frequencyHz / srHz
    val cosine = cos(w0)
    val alpha = sin(w0) / (2.0 * q)
    val a0 = 1.0 + alpha
    return Biquad(
        b0 = (1.0 - cosine) / 2.0 / a0,
        b1 = (1.0 - cosine) / a0,
        b2 = (1.0 - cosine) / 2.0 / a0,
        a1 = -2.0 * cosine / a0,
        a2 = (1.0 - alpha) / a0,
    )
}

internal fun notchBiquad(frequencyHz: Double, srHz: Double, q: Double): Biquad {
    val w0 = 2.0 * PI * frequencyHz / srHz
    val cosine = cos(w0)
    val alpha = sin(w0) / (2.0 * q)
    val a0 = 1.0 + alpha
    return Biquad(
        b0 = 1.0 / a0,
        b1 = -2.0 * cosine / a0,
        b2 = 1.0 / a0,
        a1 = -2.0 * cosine / a0,
        a2 = (1.0 - alpha) / a0,
    )
}

/** Forward-backward filtering with odd extension, matching `scipy.signal.filtfilt`. */
internal fun filtfilt(sections: List<Biquad>, x: DoubleArray, srHz: Double): DoubleArray {
    if (x.isEmpty() || sections.isEmpty()) return x.copyOf()
    val pad = min(x.size - 1, max(1, (3.0 * srHz).roundToInt()))
    val extended = oddExtend(x, pad)
    var forward = extended
    for (section in sections) forward = biquad(section, forward)
    val reversed = DoubleArray(forward.size) { forward[forward.lastIndex - it] }
    var backward = reversed
    for (section in sections) backward = biquad(section, backward)
    return DoubleArray(x.size) { backward[backward.lastIndex - (pad + it)] }
}

private fun biquad(section: Biquad, x: DoubleArray): DoubleArray {
    if (x.isEmpty()) return DoubleArray(0)
    val y = DoubleArray(x.size)
    // Seed from the constant-input steady state so a DC offset does not
    // create a false edge transient.
    val denominator = 1.0 + section.a1 + section.a2
    val dcGain = if (abs(denominator) > 1e-12) {
        (section.b0 + section.b1 + section.b2) / denominator
    } else {
        0.0
    }
    var x1 = x[0]
    var x2 = x[0]
    var y1 = x[0] * dcGain
    var y2 = y1
    for (i in x.indices) {
        val xi = x[i]
        val yi = section.b0 * xi + section.b1 * x1 + section.b2 * x2 - section.a1 * y1 - section.a2 * y2
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
    for (i in 0 until pad) out[i] = 2 * x[0] - x[reflect(pad - i, n)]
    System.arraycopy(x, 0, out, pad, n)
    for (i in 0 until pad) out[pad + n + i] = 2 * x[n - 1] - x[reflect(n - 2 - i, n)]
    return out
}

private fun reflect(i: Int, n: Int): Int {
    if (n <= 1) return 0
    val period = 2 * (n - 1)
    var x = i % period
    if (x < 0) x += period
    return if (x >= n) period - x else x
}

/** Default Butterworth order for the chain low-pass. */
const val BUTTERWORTH_ORDER_DEFAULT = 4

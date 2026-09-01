package app.galaxyvitals.data.protocol

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Direct-form II transposed cascade of second-order sections, forward only.
 *
 * DC-primes on the first sample so a constant electrode offset does not spike
 * the output, and keeps its state across calls so a streaming caller sees no
 * restart transient at a window boundary.
 */
class CausalSosFilter(private val sos: Array<DoubleArray>) {
    private val z0 = DoubleArray(sos.size)
    private val z1 = DoubleArray(sos.size)
    private var primed = false

    fun reset() {
        z0.fill(0.0)
        z1.fill(0.0)
        primed = false
    }

    fun filter(input: Float): Float = filter(input.toDouble()).toFloat()

    fun filter(input: Double): Double {
        var x = input
        if (!primed) {
            prime(x)
            primed = true
        }
        for (sectionIndex in sos.indices) {
            val section = sos[sectionIndex]
            val a0 = if (abs(section[3]) < 1e-12) 1.0 else section[3]
            val b0 = section[0] / a0
            val b1 = section[1] / a0
            val b2 = section[2] / a0
            val a1 = section[4] / a0
            val a2 = section[5] / a0
            val y = b0 * x + z0[sectionIndex]
            z0[sectionIndex] = b1 * x - a1 * y + z1[sectionIndex]
            z1[sectionIndex] = b2 * x - a2 * y
            x = y
        }
        return x
    }

    private fun prime(x0: Double) {
        var x = x0
        for (sectionIndex in sos.indices) {
            val section = sos[sectionIndex]
            val a0 = if (abs(section[3]) < 1e-12) 1.0 else section[3]
            val b0 = section[0] / a0
            val b1 = section[1] / a0
            val b2 = section[2] / a0
            val a1 = section[4] / a0
            val a2 = section[5] / a0
            val denominator = 1.0 + a1 + a2
            val gain = if (abs(denominator) > 1e-12) (b0 + b1 + b2) / denominator else 0.0
            val y = gain * x
            z1[sectionIndex] = b2 * x - a2 * y
            z0[sectionIndex] = b1 * x - a1 * y + z1[sectionIndex]
            x = y
        }
    }
}

/**
 * Causal twin of the [EcgSignalChain] conditioning used offline.
 *
 * Offline the chain is zero-phase (`filtfilt` plus a median-cascade baseline);
 * on the watch neither is available sample-by-sample, so the same 0.5-40 Hz
 * passband and the same mains notches are realised as forward-only sections.
 * The result carries a constant group delay, which shifts every R peak by the
 * same amount and therefore leaves RR *differences* - the only thing rate and
 * HRV are built from - untouched.
 */
object EcgCausalConditioning {
    /** Matches [EcgSignalChain.LINE_NOTCH_Q] and the offline harmonic ladder. */
    private const val MAX_HARMONICS = 3

    /**
     * RBJ notch on [lineHz] and its harmonics while they stay below 0.45 * fs.
     * Notch width scales with harmonic order, as in
     * [EcgSignalChain.removeLineNoise].
     */
    fun notchSos(lineHz: Double, srHz: Double): Array<DoubleArray> {
        if (lineHz <= 0.0 || srHz <= 0.0) return emptyArray()
        val sections = ArrayList<DoubleArray>(MAX_HARMONICS)
        var harmonic = lineHz
        var order = 1
        while (harmonic < 0.45 * srHz && order <= MAX_HARMONICS) {
            val w0 = 2.0 * Math.PI * harmonic / srHz
            val cosine = cos(w0)
            val alpha = sin(w0) / (2.0 * EcgSignalChain.LINE_NOTCH_Q * order)
            sections += doubleArrayOf(
                1.0, -2.0 * cosine, 1.0,
                1.0 + alpha, -2.0 * cosine, 1.0 - alpha,
            )
            harmonic += lineHz
            order++
        }
        return sections.toTypedArray()
    }

    /** SciPy `butter(4, [0.5, 40], btype="bandpass", fs=500, output="sos")`. */
    val BANDPASS_SOS_500: Array<DoubleArray> = arrayOf(
        doubleArrayOf(
            0.0021387987326912015, 0.004277597465382403, 0.0021387987326912015,
            1.0, -1.22787587909828, 0.39352306024517103,
        ),
        doubleArrayOf(1.0, 2.0, 1.0, 1.0, -1.486663673168146, 0.6949675580253452),
        doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.9882154714982394, 0.9882564156624591),
        doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.995246749028118, 0.9952864068099667),
    )

    /** The 0.5-40 Hz passband as a fresh, unprimed filter. */
    fun bandpass(srHz: Int): CausalSosFilter {
        require(srHz == EcgWearContract.DEFAULT_SR_HZ) {
            "Causal ECG conditioning is only designed for ${EcgWearContract.DEFAULT_SR_HZ} Hz"
        }
        return CausalSosFilter(BANDPASS_SOS_500)
    }
}

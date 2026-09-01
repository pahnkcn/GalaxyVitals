package app.galaxyvitals.data.protocol

/**
 * Causal Butterworth 4th-order 5–15 Hz SOS at 500 Hz.
 *
 * Coefficients are SciPy 1.x `butter(4, [5, 15], btype="bandpass", fs=500, output="sos")`.
 * Forward `sosfilt` only — never filtfilt / zero-phase.
 */
internal object EcgQrsFilter {
    const val TARGET_HZ = 500
    const val WARMUP_SAMPLES = 500

    private val SOS = arrayOf(
        doubleArrayOf(
            1.3293728898752885e-05, 2.658745779750577e-05, 1.3293728898752885e-05,
            1.0, -1.8463121662192674, 0.8647118366045192,
        ),
        doubleArrayOf(1.0, 2.0, 1.0, 1.0, -1.9096196650466386, 0.916429541365672),
        doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.899323119536047, 0.9316037802673618),
        doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.9710289923605038, 0.9751617932342163),
    )

    /**
     * Frequency the detector's group delay is quoted at: the middle of the
     * 5-15 Hz QRS band, where the envelope draws almost all of its energy.
     */
    private const val QRS_BAND_CENTRE_HZ = 10.0

    /**
     * Delay this filter adds, in samples at [TARGET_HZ].
     *
     * Forward-only filtering is not phase-linear, so every peak the envelope
     * reports sits about 36 samples (72 ms) after the R wave that produced it -
     * further than the refine window is wide. Undoing the moving-average delay
     * alone leaves that offset in place, so it is measured here from the actual
     * coefficients instead of being folded into a hand-tuned refine radius.
     */
    val GROUP_DELAY_SAMPLES: Double by lazy { groupDelaySamples(QRS_BAND_CENTRE_HZ) }

    private fun groupDelaySamples(frequencyHz: Double): Double {
        val step = 1e-4
        val omega = 2.0 * kotlin.math.PI * frequencyHz / TARGET_HZ
        var difference = phaseAt(omega + step) - phaseAt(omega - step)
        while (difference > kotlin.math.PI) difference -= 2.0 * kotlin.math.PI
        while (difference < -kotlin.math.PI) difference += 2.0 * kotlin.math.PI
        return -difference / (2.0 * step)
    }

    /** Phase of the whole cascade at digital frequency [omega], in radians. */
    private fun phaseAt(omega: Double): Double {
        var real = 1.0
        var imaginary = 0.0
        for (section in SOS) {
            val a0 = if (kotlin.math.abs(section[3]) < 1e-12) 1.0 else section[3]
            val cos1 = kotlin.math.cos(omega)
            val sin1 = kotlin.math.sin(omega)
            val cos2 = kotlin.math.cos(2.0 * omega)
            val sin2 = kotlin.math.sin(2.0 * omega)
            val numeratorReal = section[0] / a0 + section[1] / a0 * cos1 + section[2] / a0 * cos2
            val numeratorImaginary = -(section[1] / a0 * sin1 + section[2] / a0 * sin2)
            val denominatorReal = 1.0 + section[4] / a0 * cos1 + section[5] / a0 * cos2
            val denominatorImaginary = -(section[4] / a0 * sin1 + section[5] / a0 * sin2)
            val scale = denominatorReal * denominatorReal + denominatorImaginary * denominatorImaginary
            if (scale < 1e-18) continue
            val sectionReal = (numeratorReal * denominatorReal + numeratorImaginary * denominatorImaginary) / scale
            val sectionImaginary =
                (numeratorImaginary * denominatorReal - numeratorReal * denominatorImaginary) / scale
            val nextReal = real * sectionReal - imaginary * sectionImaginary
            val nextImaginary = real * sectionImaginary + imaginary * sectionReal
            real = nextReal
            imaginary = nextImaginary
        }
        return kotlin.math.atan2(imaginary, real)
    }

    fun filter(input: FloatArray): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        var x = DoubleArray(input.size) { input[it].toDouble() }
        for (section in SOS) {
            x = sosfiltSection(section, x)
        }
        return FloatArray(x.size) { x[it].toFloat() }
    }

    private fun sosfiltSection(section: DoubleArray, x: DoubleArray): DoubleArray {
        val a0 = if (kotlin.math.abs(section[3]) < 1e-12) 1.0 else section[3]
        val b0 = section[0] / a0
        val b1 = section[1] / a0
        val b2 = section[2] / a0
        val a1 = section[4] / a0
        val a2 = section[5] / a0
        val y = DoubleArray(x.size)
        var z0 = 0.0
        var z1 = 0.0
        for (i in x.indices) {
            val xi = x[i]
            val yi = b0 * xi + z0
            z0 = b1 * xi - a1 * yi + z1
            z1 = b2 * xi - a2 * yi
            y[i] = yi
        }
        return y
    }
}

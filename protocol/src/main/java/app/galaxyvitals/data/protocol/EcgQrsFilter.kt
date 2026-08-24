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

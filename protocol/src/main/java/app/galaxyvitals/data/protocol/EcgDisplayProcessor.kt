package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.EcgSample
import kotlin.math.PI

/** Builds a derived 0.5-40 Hz waveform for display without mutating raw ECG rows. */
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
        val timeStep = 1.0 / srHz
        val highPassRc = 1.0 / (2.0 * PI * HIGH_PASS_HZ)
        val highPassAlpha = highPassRc / (timeStep + highPassRc)
        val lowPassRc = 1.0 / (2.0 * PI * LOW_PASS_HZ)
        val lowPassAlpha = timeStep / (lowPassRc + timeStep)

        val output = ArrayList<EcgSample>(samples.size)
        var previousInput = samples[0].valueMv * polarity
        var highPassState = 0f
        var lowPassState = 0f
        output += samples[0].copy(valueMv = 0f)

        for (index in 1 until samples.size) {
            val current = samples[index].valueMv * polarity
            val highPassDelta = highPassState + current - previousInput
            highPassState = (highPassDelta.toDouble() * highPassAlpha).toFloat()
            previousInput = current
            val lowPassDelta = highPassState - lowPassState
            lowPassState = (lowPassDelta.toDouble() * lowPassAlpha + lowPassState).toFloat()
            output += samples[index].copy(valueMv = lowPassState)
        }
        return output
    }
}

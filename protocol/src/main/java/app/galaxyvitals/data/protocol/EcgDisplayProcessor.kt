package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.EcgSample

/**
 * Builds the derived display waveform without mutating raw ECG rows.
 *
 * Galaxy Watch `ECG_ON_DEMAND` delivers unfiltered samples: a ~15 mV electrode
 * offset that polarizes over the first second, mains interference comparable in
 * size to the R wave, and no anti-mains notch of any kind. A linear high-pass
 * rings on the polarization step badly enough to swamp the first seconds of the
 * trace, so this delegates to [EcgSignalChain], whose median-cascade baseline
 * removal absorbs the step instead and whose notch is tuned to the interference
 * actually present in the recording.
 *
 * The display uses [EcgBandwidth.MONITOR]. Anything reported as a number must
 * use [EcgBandwidth.DIAGNOSTIC] instead - a 40 Hz cutoff costs 15-20% of R-wave
 * amplitude on these captures.
 */
object EcgDisplayProcessor {
    val BANDWIDTH = EcgBandwidth.MONITOR

    fun filter(
        samples: List<EcgSample>,
        srHz: Int,
        signFactor: Int,
        polarityNormalized: Boolean,
        bandwidth: EcgBandwidth = BANDWIDTH,
    ): List<EcgSample> = process(samples, srHz, signFactor, polarityNormalized, bandwidth).samples

    /**
     * Filtered samples plus the chain metrics behind them.
     *
     * [bandwidth] defaults to the smoothed monitor trace. A caller that reports
     * numbers, or that draws a strip meant to be measured, must ask for
     * [EcgBandwidth.DIAGNOSTIC] instead.
     */
    fun process(
        samples: List<EcgSample>,
        srHz: Int,
        signFactor: Int,
        polarityNormalized: Boolean,
        bandwidth: EcgBandwidth = BANDWIDTH,
    ): DisplayResult {
        require(srHz > 0) { "ECG sample rate must be positive" }
        if (samples.isEmpty()) {
            return DisplayResult(
                samples = ArrayList(0),
                metrics = EcgSignalMetrics(
                    srHz = srHz.toDouble(),
                    nominalSrHz = srHz,
                    srMeasured = false,
                    line = null,
                    lineRmsBeforeMv = 0.0,
                    lineRmsAfterMv = 0.0,
                    baselineExcursionMv = 0.0,
                    settleSampleIndex = 0,
                    bandwidth = bandwidth,
                ),
            )
        }
        val polarity = effectivePolarity(signFactor, polarityNormalized)
        val filtered = EcgSignalChain.process(samples, srHz, polarity, bandwidth)
        val out = List(samples.size) { index ->
            samples[index].copy(valueMv = filtered.valuesMv[index].toFloat())
        }
        return DisplayResult(samples = out, metrics = filtered.metrics)
    }

    data class DisplayResult(
        val samples: List<EcgSample>,
        val metrics: EcgSignalMetrics,
    )
}

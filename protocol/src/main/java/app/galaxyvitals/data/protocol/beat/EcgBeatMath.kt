package app.galaxyvitals.data.protocol.beat

import app.galaxyvitals.data.protocol.EcgQrsFilter
import app.galaxyvitals.data.protocol.EcgStats
import kotlin.math.max
import kotlin.math.roundToInt

private const val TARGET_HZ = EcgQrsFilter.TARGET_HZ

/**
 * Duration in samples of the grid the detector runs on.
 *
 * Uses the measured [analysisSrHz] rather than the declared 500, so refractory
 * periods, T-wave windows, integration widths and the match tolerance all mean
 * what they say on a watch whose clock runs 0.33% fast.
 */
internal fun samplesForMs(ms: Int, analysisSrHz: Double): Int {
    val rate = if (analysisSrHz.isFinite() && analysisSrHz > 0.0) analysisSrHz else TARGET_HZ.toDouble()
    return max(1, (ms * rate / 1_000.0).roundToInt())
}

/** Median with the detector's convention: no beats means no rate, not zero. */
internal fun List<Double>.median(): Double = EcgStats.median(this, whenEmpty = Double.NaN)

/**
 * Undo the group delay of the trailing moving average in the envelope.
 *
 * A trailing average of width `W` delays by `(W-1)/2`, not by `W`. Shifting by
 * the full window put the 150 ms primary envelope and the 80 ms secondary
 * envelope ~35 ms apart before any noise, which spent the whole match budget
 * and depressed bSqi on clean recordings.
 *
 * [filterDelaySamples] carries the rest of the pipeline's delay - forward-only
 * band-pass filtering is not phase-linear - so the compensated index lands on
 * the R wave itself rather than 70 ms past it.
 */
internal fun delayCompensate(
    peaks: IntArray,
    windowWidth: Int,
    filterDelaySamples: Double = 0.0,
): IntArray {
    if (peaks.isEmpty()) return peaks
    val delay = (max(0, windowWidth - 1) / 2.0 + filterDelaySamples).roundToInt()
    if (delay <= 0) return peaks
    return IntArray(peaks.size) { (peaks[it] - delay).coerceAtLeast(0) }
}

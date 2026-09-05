package app.galaxyvitals.data.protocol

/**
 * Beat-to-beat intervals split into the series used for rate and the series
 * used for HRV.
 *
 * [allMs] keeps every interval that is physiologically possible at all;
 * [nnMs] keeps only those that also survive the adaptive plausibility check
 * against the running median, which is the only series HRV may be built from.
 */
data class EcgRrSeries(
    val allMs: List<Double>,
    val nnMs: List<Double>,
    /**
     * Entry `i` is true when `nnMs[i]` and `nnMs[i - 1]` were adjacent in the
     * unfiltered series. Only those pairs may enter RMSSD or pNN50: a
     * successive difference taken across a removed artifact is not a successive
     * difference. Entry `0` is always false.
     */
    val nnSuccessive: List<Boolean>,
    /** Intervals about twice the running median: a beat was not detected. */
    val missedBeatCount: Int,
    /** Intervals about half the running median: something extra was detected. */
    val extraDetectionCount: Int,
    /** Everything else that failed the plausibility check. */
    val implausibleCount: Int,
    /** Intervals examined, including the ones that were rejected. */
    val candidateCount: Int,
) {
    val correctedCount: Int get() = missedBeatCount + extraDetectionCount + implausibleCount

    /** Share of candidate intervals excluded from [nnMs]. */
    val correctedFraction: Double
        get() = if (candidateCount == 0) 0.0 else correctedCount.toDouble() / candidateCount

    companion object {
        val EMPTY = EcgRrSeries(emptyList(), emptyList(), emptyList(), 0, 0, 0, 0)
    }
}

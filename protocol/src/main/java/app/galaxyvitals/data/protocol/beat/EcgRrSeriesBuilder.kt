package app.galaxyvitals.data.protocol.beat

import app.galaxyvitals.data.protocol.EcgBeatDetectorConfig
import app.galaxyvitals.data.protocol.EcgQrsFilter
import app.galaxyvitals.data.protocol.EcgRrSeries

private const val TARGET_HZ = EcgQrsFilter.TARGET_HZ

/**
 * Split consecutive intervals into the rate series and the HRV series.
 *
 * A fixed 333-1500 ms window cannot tell a missed beat from bradycardia, so
 * plausibility is judged against a running median of the recent accepted
 * intervals instead. Intervals at roughly twice or half that median are
 * classified - missed beat, extra detection - and counted rather than
 * silently dropped, because the count is what tells the HRV stage whether
 * the recording is worth reporting at all.
 */
internal fun rrSeries(
    positions: DoubleArray,
    config: EcgBeatDetectorConfig,
    analysisSrHz: Double,
): EcgRrSeries {
    if (positions.size < 2) return EcgRrSeries.EMPTY
    val rate = if (analysisSrHz.isFinite() && analysisSrHz > 0.0) analysisSrHz else TARGET_HZ.toDouble()
    val raw = DoubleArray(positions.size - 1) { index ->
        (positions[index + 1] - positions[index]) * 1_000.0 / rate
    }
    val physiological = raw.filter { it in config.minRrMs..config.maxRrMs }
    var reference = if (physiological.isEmpty()) raw.toList().median() else physiological.median()
    val recent = ArrayDeque<Double>()
    val allMs = ArrayList<Double>(raw.size)
    val nnMs = ArrayList<Double>(raw.size)
    val nnSuccessive = ArrayList<Boolean>(raw.size)
    var previousAccepted = -2
    var missed = 0
    var extra = 0
    var implausible = 0
    val tolerance = config.rrMultipleTolerance
    for (index in raw.indices) {
        val interval = raw[index]
        val physiologicallyPossible = interval in config.minRrMs..config.maxRrMs
        if (physiologicallyPossible) allMs += interval
        val ratio = if (reference > 0.0 && reference.isFinite()) interval / reference else 1.0
        when {
            physiologicallyPossible && ratio >= config.rrPlausibleLow && ratio <= config.rrPlausibleHigh -> {
                nnSuccessive += nnMs.isNotEmpty() && index == previousAccepted + 1
                nnMs += interval
                previousAccepted = index
                recent.addLast(interval)
                while (recent.size > config.rrMedianWindow) recent.removeFirst()
                reference = recent.toList().median()
            }
            ratio >= 2.0 * (1.0 - tolerance) && ratio <= 2.0 * (1.0 + tolerance) -> missed++
            ratio >= 0.5 * (1.0 - tolerance) && ratio <= 0.5 * (1.0 + tolerance) -> extra++
            else -> implausible++
        }
    }
    return EcgRrSeries(
        allMs = allMs,
        nnMs = nnMs,
        nnSuccessive = nnSuccessive,
        missedBeatCount = missed,
        extraDetectionCount = extra,
        implausibleCount = implausible,
        candidateCount = raw.size,
    )
}

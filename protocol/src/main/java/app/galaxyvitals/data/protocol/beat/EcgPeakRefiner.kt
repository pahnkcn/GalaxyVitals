package app.galaxyvitals.data.protocol.beat

import app.galaxyvitals.data.protocol.EcgBeatDetectorConfig
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Peak positions at whole-sample and sub-sample resolution. */
internal class RefinedPeaks(
    val indices: IntArray,
    val positions: DoubleArray,
) {
    companion object {
        val EMPTY = RefinedPeaks(IntArray(0), DoubleArray(0))
    }
}

/**
 * Move each envelope peak onto the nearest QRS extremum of the conditioned
 * trace.
 *
 * Searches `|x|` so an inverted or biphasic R lands on its own peak instead
 * of on whatever positive feature happens to be nearby, and resolves two
 * detections that collapse onto one sample by keeping the closer of the two
 * and re-searching the other rather than dropping a beat.
 */
internal fun refinePeaks(
    peaks: IntArray,
    signal: FloatArray,
    config: EcgBeatDetectorConfig,
    analysisSrHz: Double,
): RefinedPeaks {
    if (peaks.isEmpty() || signal.isEmpty()) return RefinedPeaks.EMPTY
    val radius = samplesForMs(config.refineRadiusMs, analysisSrHz)
    val best = IntArray(peaks.size) { -1 }
    val excluded = Array(peaks.size) { HashSet<Int>() }
    val claimedBy = HashMap<Int, Int>(peaks.size * 2)
    val queue = ArrayDeque<Int>(peaks.size)
    for (slot in peaks.indices) queue.addLast(slot)
    var guard = 0
    val guardLimit = peaks.size * 8 + 16
    while (queue.isNotEmpty() && guard++ < guardLimit) {
        val slot = queue.removeFirst()
        val centre = peaks[slot]
        val found = argMaxAbs(signal, centre - radius, centre + radius, excluded[slot]) ?: continue
        val holder = claimedBy[found]
        if (holder == null || holder == slot) {
            claimedBy[found] = slot
            best[slot] = found
            continue
        }
        if (abs(centre - found) < abs(peaks[holder] - found)) {
            claimedBy[found] = slot
            best[slot] = found
            best[holder] = -1
            excluded[holder] += found
            queue.addLast(holder)
        } else {
            excluded[slot] += found
            queue.addLast(slot)
        }
    }
    val positions = ArrayList<Double>(peaks.size)
    for (slot in peaks.indices) {
        val index = best[slot]
        if (index >= 0) positions += subSamplePeak(signal, index)
    }
    positions.sort()
    return RefinedPeaks(
        indices = IntArray(positions.size) { positions[it].roundToInt() },
        positions = DoubleArray(positions.size) { positions[it] },
    )
}

private fun argMaxAbs(
    signal: FloatArray,
    fromIndex: Int,
    toIndex: Int,
    excluded: Set<Int>,
): Int? {
    val from = fromIndex.coerceAtLeast(0)
    val to = toIndex.coerceAtMost(signal.lastIndex)
    var best = -1
    var bestMagnitude = -1.0
    for (index in from..to) {
        if (index in excluded) continue
        val magnitude = abs(signal[index]).toDouble()
        if (magnitude > bestMagnitude) {
            bestMagnitude = magnitude
            best = index
        }
    }
    return if (best < 0) null else best
}

/**
 * Parabolic vertex through the refined sample and its neighbours.
 *
 * Without it RR resolution is one whole sample - 2 ms at 500 Hz - which puts
 * a floor under RMSSD that is the same order as the quantity being measured.
 */
internal fun subSamplePeak(signal: FloatArray, index: Int): Double {
    if (index <= 0 || index >= signal.lastIndex) return index.toDouble()
    val before = abs(signal[index - 1]).toDouble()
    val here = abs(signal[index]).toDouble()
    val after = abs(signal[index + 1]).toDouble()
    val denominator = before - 2.0 * here + after
    if (abs(denominator) < 1e-12) return index.toDouble()
    val delta = 0.5 * (before - after) / denominator
    if (!delta.isFinite()) return index.toDouble()
    return index + delta.coerceIn(-0.5, 0.5)
}

internal fun matchPeaks(
    primary: RefinedPeaks,
    secondary: RefinedPeaks,
    tolerance: Int,
): RefinedPeaks {
    val positions = ArrayList<Double>(min(primary.indices.size, secondary.indices.size))
    var j = 0
    for (i in primary.indices.indices) {
        val peak = primary.indices[i]
        while (j < secondary.indices.size && secondary.indices[j] < peak - tolerance) j++
        if (j < secondary.indices.size && abs(secondary.indices[j] - peak) <= tolerance) {
            positions += (primary.positions[i] + secondary.positions[j]) / 2.0
            j++
        }
    }
    return RefinedPeaks(
        indices = IntArray(positions.size) { positions[it].roundToInt() },
        positions = DoubleArray(positions.size) { positions[it] },
    )
}

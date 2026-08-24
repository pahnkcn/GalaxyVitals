package app.galaxyvitals.data.protocol

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

data class WaveformPoint(
    val sampleIndex: Long,
    val valueMv: Float,
    val startsNewSegment: Boolean = false,
)

data class WaveformScale(
    val centerMv: Float,
    val halfRangeMv: Float,
) {
    companion object {
        val Default = WaveformScale(centerMv = 0f, halfRangeMv = 0.5f)
    }
}

object EcgWaveformGeometry {
    fun reduceM4(
        points: List<WaveformPoint>,
        physicalPixelWidth: Int,
    ): List<WaveformPoint> {
        if (points.isEmpty()) return emptyList()
        val bucketCount = (physicalPixelWidth / 2).coerceAtLeast(1)
        val reduced = ArrayList<WaveformPoint>(min(points.size, bucketCount * 4 * 4))
        for (segment in splitSegments(points)) {
            reduceSegment(segment, bucketCount, reduced)
        }
        return reduced
    }

    fun nextScale(
        points: List<WaveformPoint>,
        previous: WaveformScale,
        deltaMs: Long,
    ): WaveformScale {
        if (points.isEmpty()) return previous
        val values = FloatArray(points.size) { points[it].valueMv }
        val median = median(values)
        val absDev = FloatArray(values.size) { abs(values[it] - median) }
        absDev.sort()
        val targetHalfRange = (1.2f * percentileSorted(absDev, 0.995))
            .coerceIn(MIN_HALF_RANGE_MV, MAX_HALF_RANGE_MV)
        val alpha = (1.0 - exp(-deltaMs.coerceAtLeast(0L) / SCALE_TAU_MS)).toFloat()
        val halfRange = if (targetHalfRange > previous.halfRangeMv) {
            targetHalfRange
        } else {
            previous.halfRangeMv + alpha * (targetHalfRange - previous.halfRangeMv)
        }
        val center = previous.centerMv + alpha * (median - previous.centerMv)
        return WaveformScale(centerMv = center, halfRangeMv = halfRange)
    }

    private fun splitSegments(points: List<WaveformPoint>): List<List<WaveformPoint>> {
        val segments = ArrayList<ArrayList<WaveformPoint>>()
        var current = ArrayList<WaveformPoint>()
        for (index in points.indices) {
            val point = points[index]
            if (current.isNotEmpty() && point.startsNewSegment) {
                segments += current
                current = ArrayList()
            }
            current += point
        }
        if (current.isNotEmpty()) segments += current
        return segments
    }

    private fun reduceSegment(
        segment: List<WaveformPoint>,
        bucketCount: Int,
        out: ArrayList<WaveformPoint>,
    ) {
        if (segment.isEmpty()) return
        val startOut = out.size
        val buckets = min(bucketCount, segment.size).coerceAtLeast(1)
        if (segment.size <= buckets) {
            out.addAll(segment)
        } else {
            for (bucket in 0 until buckets) {
                val start = (bucket.toLong() * segment.size / buckets).toInt()
                val endExclusive = (((bucket + 1L) * segment.size) / buckets).toInt()
                    .coerceAtLeast(start + 1)
                emitBucket(segment, start, endExclusive, out)
            }
        }
        if (out.size == startOut) return
        if (!out[startOut].startsNewSegment) {
            out[startOut] = out[startOut].copy(startsNewSegment = true)
        }
        for (index in startOut + 1 until out.size) {
            if (out[index].startsNewSegment) {
                out[index] = out[index].copy(startsNewSegment = false)
            }
        }
    }

    private fun emitBucket(
        segment: List<WaveformPoint>,
        start: Int,
        endExclusive: Int,
        out: ArrayList<WaveformPoint>,
    ) {
        var minIndex = start
        var maxIndex = start
        for (index in start + 1 until endExclusive) {
            val value = segment[index].valueMv
            if (value < segment[minIndex].valueMv) minIndex = index
            if (value > segment[maxIndex].valueMv) maxIndex = index
        }
        val indices = intArrayOf(start, minIndex, maxIndex, endExclusive - 1)
        indices.sort()
        var previous = -1
        for (index in indices) {
            if (index != previous) {
                out += segment[index]
                previous = index
            }
        }
    }

    private fun median(values: FloatArray): Float {
        val sorted = values.copyOf()
        sorted.sort()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2f
        } else {
            sorted[mid]
        }
    }

    private fun percentileSorted(sorted: FloatArray, p: Double): Float {
        if (sorted.size == 1) return sorted[0]
        val rank = p * (sorted.size - 1)
        val lo = rank.toInt().coerceIn(0, sorted.lastIndex)
        val hi = (lo + 1).coerceAtMost(sorted.lastIndex)
        val frac = (rank - lo).toFloat()
        return sorted[lo] + frac * (sorted[hi] - sorted[lo])
    }

    private const val MIN_HALF_RANGE_MV = 0.5f
    private const val MAX_HALF_RANGE_MV = 5.0f
    private const val SCALE_TAU_MS = 5_000.0
}

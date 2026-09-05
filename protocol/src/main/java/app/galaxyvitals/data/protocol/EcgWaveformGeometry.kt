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
        firstSampleIndex: Long,
        lastSampleIndex: Long,
    ): List<WaveformPoint> {
        if (points.isEmpty()) return emptyList()
        val bucketCount = (physicalPixelWidth / 2).coerceAtLeast(1)
        val maxPoints = bucketCount * 4
        val inDomain = points.filter {
            it.sampleIndex in firstSampleIndex..lastSampleIndex
        }
        if (inDomain.isEmpty()) return emptyList()
        val selected = selectSegments(splitSegments(inDomain), bucketCount)
        val spans = LongArray(selected.size) { index ->
            val segment = selected[index]
            (segment.last().sampleIndex - segment.first().sampleIndex).coerceAtLeast(1L)
        }
        val allocated = allocateBuckets(spans, bucketCount)
        val reduced = ArrayList<WaveformPoint>(min(inDomain.size, maxPoints))
        for (index in selected.indices) {
            val buckets = allocated[index]
            if (buckets > 0) reduceSegment(selected[index], buckets, reduced)
        }
        return if (reduced.size <= maxPoints) reduced else reduced.take(maxPoints)
    }

    fun mapYToCanvas(
        valueMv: Float,
        centerMv: Float,
        halfRangeMv: Float,
        heightPx: Float,
        strokeWidthPx: Float,
    ): Float {
        val range = halfRangeMv.coerceAtLeast(1e-6f)
        val y = heightPx / 2f - ((valueMv - centerMv) / range) * (heightPx / 2f)
        val pad = (strokeWidthPx / 2f).coerceAtLeast(0f)
        val maxY = (heightPx - pad).coerceAtLeast(pad)
        return y.coerceIn(pad, maxY)
    }

    fun nextScale(
        points: List<WaveformPoint>,
        previous: WaveformScale,
        deltaMs: Long,
    ): WaveformScale {
        if (points.isEmpty()) return previous
        val values = FloatArray(points.size) { points[it].valueMv }
        val median = EcgStats.median(values)
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

    private fun selectSegments(
        segments: List<List<WaveformPoint>>,
        maxSegments: Int,
    ): List<List<WaveformPoint>> {
        if (segments.size <= maxSegments) return segments
        val keep = segments.mapIndexed { index, segment ->
            val span = (segment.last().sampleIndex - segment.first().sampleIndex).coerceAtLeast(1L)
            IndexedValue(index, span)
        }
            .sortedWith(compareByDescending<IndexedValue<Long>> { it.value }.thenBy { it.index })
            .take(maxSegments)
            .map { it.index }
            .toHashSet()
        return segments.filterIndexed { index, _ -> index in keep }
    }

    private fun allocateBuckets(spans: LongArray, bucketCount: Int): IntArray {
        val allocated = IntArray(spans.size)
        if (spans.isEmpty() || bucketCount <= 0) return allocated
        val total = spans.sum()
        if (total <= 0L) {
            allocated[0] = bucketCount
            return allocated
        }
        var assigned = 0
        val remainders = LongArray(spans.size)
        for (index in spans.indices) {
            val product = spans[index] * bucketCount
            allocated[index] = (product / total).toInt()
            remainders[index] = product % total
            assigned += allocated[index]
        }
        val order = remainders.indices.sortedWith(
            compareByDescending<Int> { remainders[it] }.thenBy { it },
        )
        var leftover = bucketCount - assigned
        var cursor = 0
        while (leftover > 0 && order.isNotEmpty()) {
            allocated[order[cursor % order.size]] += 1
            leftover -= 1
            cursor += 1
        }
        return allocated
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

package app.galaxyvitals.ui.components

import app.galaxyvitals.data.protocol.EcgWaveformGeometry
import app.galaxyvitals.data.protocol.WaveformPoint
import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.domain.EcgSampleFlags

/**
 * Turns stored samples into the points a strip is drawn from.
 *
 * x follows `sampleIndex`, never the captured timestamp: `ECG_ON_DEMAND`
 * batch-quantises its timestamps, so many samples share one and time taken from
 * them would collapse a whole batch onto a single column.
 */
internal fun toWaveformPoints(samples: List<EcgSample>): List<WaveformPoint> {
    val gapFlags = EcgSampleFlags.TIMESTAMP_GAP or EcgSampleFlags.SEQUENCE_GAP
    return samples.mapIndexed { index, sample ->
        WaveformPoint(
            sampleIndex = sample.sampleIndex.toLong(),
            valueMv = sample.valueMv,
            startsNewSegment = index == 0 || sample.flags and gapFlags != 0,
        )
    }
}

/**
 * Reduces a trace to what the surface can actually resolve. M4 keeps the first,
 * last, min and max of every bucket, so a narrow QRS survives instead of being
 * averaged into the baseline around it.
 */
internal fun reduceWaveform(
    samples: List<EcgSample>,
    physicalPixelWidth: Int,
): List<WaveformPoint> {
    val points = toWaveformPoints(samples)
    if (points.isEmpty()) return emptyList()
    return EcgWaveformGeometry.reduceM4(
        points = points,
        physicalPixelWidth = physicalPixelWidth,
        firstSampleIndex = points.first().sampleIndex,
        lastSampleIndex = points.last().sampleIndex,
    )
}

/**
 * A trace reduced to a fixed number of columns for a preview.
 *
 * Each bucket contributes its minimum and its maximum, in the order they occur,
 * so a QRS one sample wide still reaches full height instead of being averaged
 * into the baseline. The result is drawn at even spacing: a preview shows the
 * shape of a recording, and anything measured is measured on the real strip.
 */
fun previewEnvelope(samples: List<EcgSample>, columns: Int): List<Float> {
    if (samples.isEmpty() || columns <= 0) return emptyList()
    if (samples.size <= columns * 2) return samples.map { it.valueMv }
    val out = ArrayList<Float>(columns * 2)
    val perBucket = samples.size.toDouble() / columns
    for (bucket in 0 until columns) {
        val from = (bucket * perBucket).toInt()
        val to = (((bucket + 1) * perBucket).toInt()).coerceAtMost(samples.size)
        if (to <= from) continue
        var min = samples[from].valueMv
        var max = min
        var minAt = from
        var maxAt = from
        for (i in from until to) {
            val v = samples[i].valueMv
            if (v < min) { min = v; minAt = i }
            if (v > max) { max = v; maxAt = i }
        }
        if (minAt <= maxAt) { out.add(min); out.add(max) } else { out.add(max); out.add(min) }
    }
    return out
}

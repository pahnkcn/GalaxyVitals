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

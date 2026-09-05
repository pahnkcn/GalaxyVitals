package app.galaxyvitals.data.protocol.beat

import app.galaxyvitals.data.protocol.EcgBeatDetectorConfig
import app.galaxyvitals.data.protocol.EcgQrsFilter
import app.galaxyvitals.data.protocol.EcgRrSeries
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What one resampled window yielded, before the reporting gates are applied. */
internal data class SegmentDetections(
    val primary: IntArray,
    val secondary: IntArray,
    val matched: IntArray,
    val rr: EcgRrSeries,
    val envelopeSnr: Double,
    val dominantDeflection: Double,
)

private data class PeakDetection(
    val indices: IntArray,
    val signalNoise: Double,
)

internal fun detectWithOptionalDualPolarity(
    oriented: FloatArray,
    config: EcgBeatDetectorConfig,
    analysisSrHz: Double,
): SegmentDetections {
    val upright = detectOnResampled(oriented, config, analysisSrHz)
    if (!config.dualPolarity) return upright
    val polarityInverted = upright.dominantDeflection <= 0.0
    val bsqiPoor = bSqi(upright) < config.minBsqi
    if (!polarityInverted && !bsqiPoor) return upright
    val inverted = detectOnResampled(
        FloatArray(oriented.size) { -oriented[it] },
        config,
        analysisSrHz,
    )
    return betterPolarity(upright, inverted)
}

private fun betterPolarity(left: SegmentDetections, right: SegmentDetections): SegmentDetections {
    val cmp = compareValuesBy(
        right,
        left,
        { bSqi(it) },
        { it.matched.size },
        { it.dominantDeflection },
    )
    return if (cmp > 0) right else left
}

private fun bSqi(detection: SegmentDetections): Double {
    val denominator = detection.primary.size + detection.secondary.size - detection.matched.size
    return if (denominator == 0) 0.0 else detection.matched.size.toDouble() / denominator
}

private fun detectOnResampled(
    oriented: FloatArray,
    config: EcgBeatDetectorConfig,
    analysisSrHz: Double,
): SegmentDetections {
    if (oriented.size <= EcgQrsFilter.WARMUP_SAMPLES) {
        return SegmentDetections(
            IntArray(0),
            IntArray(0),
            IntArray(0),
            EcgRrSeries.EMPTY,
            0.0,
            0.0,
        )
    }
    val filtered = EcgQrsFilter.filter(oriented)
    val start = EcgQrsFilter.WARMUP_SAMPLES
    val derivative = fivePointDerivative(filtered)
    val squared = FloatArray(filtered.size) { derivative[it] * derivative[it] }
    val primaryWidth = samplesForMs(config.primaryIntegrationMs, analysisSrHz)
    val primaryEnv = movingAverage(squared, primaryWidth)
    val absDeriv = FloatArray(filtered.size)
    for (index in 1 until filtered.size) {
        absDeriv[index] = abs(filtered[index] - filtered[index - 1])
    }
    val secondaryWidth = samplesForMs(config.secondaryIntegrationMs, analysisSrHz)
    val secondaryEnv = movingAverage(absDeriv, secondaryWidth)
    val primaryRaw = detectPeaks(
        envelope = primaryEnv,
        startIndex = start,
        refractoryMs = config.primaryRefractoryMs,
        twaveMs = config.twaveMs,
        searchBack = config.searchback,
        humpSamples = primaryWidth,
        config = config,
        analysisSrHz = analysisSrHz,
    )
    val secondaryRaw = detectPeaks(
        envelope = secondaryEnv,
        startIndex = start,
        refractoryMs = config.secondaryRefractoryMs,
        twaveMs = if (config.secondaryTwave) config.twaveMs else null,
        searchBack = false,
        humpSamples = secondaryWidth,
        config = config,
        analysisSrHz = analysisSrHz,
    )
    val primary = refinePeaks(
        delayCompensate(primaryRaw.indices, primaryWidth, EcgQrsFilter.GROUP_DELAY_SAMPLES),
        oriented,
        config,
        analysisSrHz,
    )
    val secondary = refinePeaks(
        delayCompensate(secondaryRaw.indices, secondaryWidth, EcgQrsFilter.GROUP_DELAY_SAMPLES),
        oriented,
        config,
        analysisSrHz,
    )
    val matched = matchPeaks(primary, secondary, samplesForMs(config.matchToleranceMs, analysisSrHz))
    return SegmentDetections(
        primary = primary.indices,
        secondary = secondary.indices,
        matched = matched.indices,
        rr = rrSeries(matched.positions, config, analysisSrHz),
        envelopeSnr = primaryRaw.signalNoise,
        dominantDeflection = dominantDeflection(oriented, primary.indices, analysisSrHz),
    )
}

private fun dominantDeflection(
    oriented: FloatArray,
    peaks: IntArray,
    analysisSrHz: Double,
): Double {
    if (peaks.isEmpty() || oriented.isEmpty()) return 0.0
    val radius = samplesForMs(80, analysisSrHz)
    val extremes = ArrayList<Double>(peaks.size)
    for (peak in peaks) {
        val from = (peak - radius).coerceAtLeast(0)
        val to = (peak + radius).coerceAtMost(oriented.lastIndex)
        var best = oriented[from]
        var bestAbs = abs(best)
        for (index in from..to) {
            val value = oriented[index]
            val magnitude = abs(value)
            if (magnitude > bestAbs) {
                bestAbs = magnitude
                best = value
            }
        }
        extremes += best.toDouble()
    }
    return extremes.median()
}

private fun detectPeaks(
    envelope: FloatArray,
    startIndex: Int,
    refractoryMs: Int,
    twaveMs: Int?,
    searchBack: Boolean,
    humpSamples: Int,
    config: EcgBeatDetectorConfig,
    analysisSrHz: Double,
): PeakDetection {
    if (startIndex >= envelope.lastIndex) return PeakDetection(IntArray(0), 0.0)
    val refractory = samplesForMs(refractoryMs, analysisSrHz)
    // Never climb far enough to reach the next beat's hump.
    val hump = humpSamples.coerceIn(1, max(1, refractory / 2))
    val twave = twaveMs?.let { samplesForMs(it, analysisSrHz) } ?: 0
    val candidates = findLocalMaxima(envelope, startIndex)
    if (candidates.isEmpty()) return PeakDetection(IntArray(0), 0.0)

    val learnSamples = samplesForMs(config.learnSeconds * 1_000, analysisSrHz)
    val learnEnd = min(envelope.size, startIndex + learnSamples)
    var maxTrain = 0.0
    var sumTrain = 0.0
    var nTrain = 0
    for (index in startIndex until learnEnd) {
        val value = envelope[index].toDouble()
        if (value > maxTrain) maxTrain = value
        sumTrain += value
        nTrain++
    }
    var spki = maxTrain / 3.0
    var npki = if (nTrain == 0) 0.0 else (sumTrain / nTrain) / 2.0
    if (spki < npki) spki = npki
    fun threshold(): Double = npki + config.thresholdNoiseWeight * (spki - npki)

    val qrs = ArrayList<Int>()
    val qrsAmp = ArrayList<Double>()
    val rr = ArrayList<Int>()
    fun meanRr(): Double {
        if (rr.isEmpty()) return 0.0
        val from = max(0, rr.size - 8)
        var sum = 0.0
        for (index in from until rr.size) sum += rr[index]
        return sum / (rr.size - from)
    }

    fun accept(index: Int, value: Double) {
        if (qrs.isNotEmpty()) rr += index - qrs.last()
        qrs += index
        qrsAmp += value
        spki = config.ewma * value + (1.0 - config.ewma) * spki
        if (spki < npki) spki = npki
    }

    fun trySearchBack(untilIndex: Int): Boolean {
        if (!searchBack || qrs.isEmpty()) return false
        val mean = meanRr()
        if (mean <= 0.0) return false
        val last = qrs.last()
        val limit = (config.searchbackRr * mean).toInt()
        if (untilIndex - last <= limit) return false
        val from = last + refractory
        val to = min(untilIndex - refractory, last + limit)
        if (to <= from) return false
        val half = config.searchbackScale * threshold()
        var bestIndex = -1
        var bestValue = half
        for (index in from until to) {
            if (index <= 0 || index >= envelope.lastIndex) continue
            val value = envelope[index].toDouble()
            if (value <= bestValue) continue
            if (envelope[index] >= envelope[index - 1] && envelope[index] > envelope[index + 1]) {
                bestValue = value
                bestIndex = index
            }
        }
        if (bestIndex < 0) return false
        accept(bestIndex, bestValue)
        return true
    }

    var cursor = 0
    while (cursor < candidates.size) {
        val index = candidates[cursor]
        val value = envelope[index].toDouble()
        if (qrs.isNotEmpty() && index - qrs.last() < refractory) {
            cursor++
            continue
        }
        if (trySearchBack(index)) continue
        val thr = threshold()
        val noiseThr = 0.5 * thr
        if (value >= thr) {
            // Climb to the top of this envelope hump. The first local maximum
            // to cross the threshold sits on the rising flank, and at high
            // rates the refractory period expires part-way up it - which put
            // the reported peak up to 90 ms before the true one and left the
            // delay correction with nothing consistent to correct.
            var bestCursor = cursor
            var peakIndex = index
            var peakValue = value
            var scan = cursor + 1
            while (scan < candidates.size && candidates[scan] - index <= hump) {
                val scanValue = envelope[candidates[scan]].toDouble()
                if (scanValue > peakValue) {
                    peakValue = scanValue
                    peakIndex = candidates[scan]
                    bestCursor = scan
                }
                scan++
            }
            val last = qrs.lastOrNull()
            if (twave > 0 && last != null && peakIndex - last <= twave && qrsAmp.isNotEmpty()) {
                val previous = qrsAmp.last()
                val weakerThanQrs = peakValue < config.twaveAmpRatio * previous
                val shallowerSlope = abs(slope(envelope, peakIndex, analysisSrHz)) <=
                    0.5 * abs(slope(envelope, last, analysisSrHz))
                if (weakerThanQrs || shallowerSlope) {
                    npki = config.ewma * peakValue + (1.0 - config.ewma) * npki
                    cursor++
                    continue
                }
            }
            accept(peakIndex, peakValue)
            cursor = bestCursor + 1
            continue
        } else if (value > noiseThr) {
            npki = config.ewma * value + (1.0 - config.ewma) * npki
        }
        cursor++
    }
    if (searchBack && qrs.isNotEmpty()) {
        while (trySearchBack(envelope.size)) {
        }
    }
    if (config.minPeakToMedian > 0.0 && qrs.size >= 3) {
        val medianAmp = qrsAmp.median()
        if (medianAmp > 0.0) {
            val keep = ArrayList<Int>()
            val keepAmp = ArrayList<Double>()
            val floor = config.minPeakToMedian * medianAmp
            for (index in qrs.indices) {
                if (qrsAmp[index] >= floor) {
                    keep += qrs[index]
                    keepAmp += qrsAmp[index]
                }
            }
            qrs.clear()
            qrs.addAll(keep)
            qrsAmp.clear()
            qrsAmp.addAll(keepAmp)
        }
    }
    val signalNoise = if (npki <= 1e-12) {
        if (spki > 0.0) 99.0 else 0.0
    } else {
        spki / npki
    }
    return PeakDetection(qrs.toIntArray(), signalNoise)
}

private fun slope(envelope: FloatArray, peak: Int, analysisSrHz: Double): Double {
    val width = samplesForMs(75, analysisSrHz)
    val from = (peak - width).coerceAtLeast(0)
    val to = peak.coerceAtMost(envelope.lastIndex)
    if (to <= from) return 0.0
    return (envelope[to] - envelope[from]).toDouble() / (to - from)
}

private fun findLocalMaxima(envelope: FloatArray, startIndex: Int): IntArray {
    val peaks = ArrayList<Int>()
    val from = max(1, startIndex)
    val to = envelope.lastIndex
    var index = from
    while (index < to) {
        if (envelope[index] > 0f &&
            envelope[index] >= envelope[index - 1] &&
            envelope[index] > envelope[index + 1]
        ) {
            peaks += index
        }
        index++
    }
    return peaks.toIntArray()
}

private fun fivePointDerivative(signal: FloatArray): FloatArray {
    val out = FloatArray(signal.size)
    for (index in 2 until signal.size - 2) {
        out[index] = (
            -signal[index - 2] - 2f * signal[index - 1] +
                2f * signal[index + 1] + signal[index + 2]
            ) / 8f
    }
    return out
}

private fun movingAverage(values: FloatArray, width: Int): FloatArray {
    val window = max(1, width)
    val out = FloatArray(values.size)
    var sum = 0.0
    for (index in values.indices) {
        sum += values[index]
        if (index >= window) sum -= values[index - window]
        out[index] = (sum / min(window, index + 1)).toFloat()
    }
    return out
}

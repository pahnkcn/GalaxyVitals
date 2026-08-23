package app.galaxyvitals.wear.ui

import app.galaxyvitals.data.protocol.EcgWearContract
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Live display BPM.
 *
 * Samsung `ECG_ON_DEMAND` delivers `PPG_GREEN` on the same tracker as `ECG_MV`.
 * Pulse-rate from that PPG is the primary live number. ECG R-peaks are only a
 * fallback when PPG is missing.
 */
object LiveBpmEstimator {
    fun estimateBpm(
        samples: List<Float>,
        ppgGreen: List<Int> = emptyList(),
        srHz: Int = EcgWearContract.DEFAULT_SR_HZ,
    ): Int? = estimateFromPpg(ppgGreen, srHz) ?: estimateFromEcg(samples, srHz)

    private fun estimateFromPpg(ppg: List<Int>, srHz: Int): Int? {
        if (srHz <= 0 || ppg.size < (srHz * MIN_SECONDS).toInt()) return null
        val x = FloatArray(ppg.size) { ppg[it].toFloat() }
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for (value in x) {
            if (value < min) min = value
            if (value > max) max = value
        }
        if (!(max - min > MIN_PPG_SWING)) return null
        val detrend = FloatArray(x.size)
        val width = max(1, srHz / 2)
        var sum = 0.0
        for (index in x.indices) {
            sum += x[index]
            if (index >= width) sum -= x[index - width]
            detrend[index] = x[index] - (sum / minOf(width, index + 1)).toFloat()
        }
        return pulseBpm(detrend, srHz)
    }

    private fun pulseBpm(signal: FloatArray, srHz: Int): Int? {
        val envelope = FloatArray(signal.size)
        for (index in 1 until signal.size) {
            val delta = signal[index] - signal[index - 1]
            envelope[index] = if (delta > 0f) delta else 0f
        }
        val integrated = movingAverage(envelope, max(1, (0.12 * srHz).toInt()))
        val mean = integrated.average()
        var variance = 0.0
        for (value in integrated) {
            val delta = value - mean
            variance += delta * delta
        }
        val threshold = mean + 0.50 * sqrt(variance / integrated.size)
        val refractory = (srHz * PPG_REFRACTORY_MS / 1_000).coerceAtLeast(1)
        val peaks = ArrayList<Int>()
        var index = 1
        while (index < integrated.lastIndex) {
            if (integrated[index] >= threshold &&
                integrated[index] >= integrated[index - 1] &&
                integrated[index] > integrated[index + 1]
            ) {
                if (peaks.isEmpty() || index - peaks.last() >= refractory) peaks += index
                else if (integrated[index] > integrated[peaks.last()]) peaks[peaks.lastIndex] = index
                index += refractory / 2
                continue
            }
            index++
        }
        return bpmFromPeaks(peaks, srHz)
    }

    private fun estimateFromEcg(samples: List<Float>, srHz: Int): Int? {
        val upright = estimateOriented(samples, invert = false, srHz)
        val inverted = estimateOriented(samples, invert = true, srHz)
        return when {
            upright == null -> inverted?.bpm
            inverted == null -> upright.bpm
            inverted.cv < upright.cv -> inverted.bpm
            else -> upright.bpm
        }
    }

    private fun estimateOriented(samples: List<Float>, invert: Boolean, srHz: Int): Estimate? {
        if (srHz <= 0 || samples.size < (srHz * MIN_SECONDS).toInt()) return null
        val signed = FloatArray(samples.size) { index ->
            if (invert) -samples[index] else samples[index]
        }
        val p99 = percentile(signed, 0.99f)
        if (p99 < MIN_AMPLITUDE_MV) return null
        val threshold = p99 * PEAK_FRACTION
        val candidates = ArrayList<Int>()
        for (index in 1 until signed.lastIndex) {
            if (signed[index] >= threshold &&
                signed[index] >= signed[index - 1] &&
                signed[index] > signed[index + 1] &&
                peakWidth(signed, index) >= MIN_PEAK_WIDTH
            ) {
                candidates += index
            }
        }
        if (candidates.size < MIN_PEAKS) return null
        candidates.sortByDescending { signed[it] }
        val refractory = (srHz * ECG_REFRACTORY_MS / 1_000).coerceAtLeast(1)
        val accepted = ArrayList<Int>()
        for (index in candidates) {
            if (accepted.none { abs(it - index) < refractory }) accepted += index
        }
        if (accepted.size < MIN_PEAKS) return null
        accepted.sort()
        val bpm = bpmFromPeaks(accepted, srHz) ?: return null
        val intervals = ArrayList<Int>(accepted.size - 1)
        for (peakIndex in 1 until accepted.size) {
            val dt = accepted[peakIndex] - accepted[peakIndex - 1]
            if (dt > 0) intervals += dt
        }
        if (intervals.size < MIN_INTERVALS) return null
        val mean = intervals.average()
        if (mean <= 0.0) return null
        var variance = 0.0
        intervals.forEach { sample ->
            val delta = sample - mean
            variance += delta * delta
        }
        return Estimate(bpm, sqrt(variance / intervals.size) / mean)
    }

    private fun bpmFromPeaks(peaks: List<Int>, srHz: Int): Int? {
        if (peaks.size < MIN_PEAKS) return null
        val intervals = ArrayList<Int>(peaks.size - 1)
        for (peakIndex in 1 until peaks.size) {
            val dt = peaks[peakIndex] - peaks[peakIndex - 1]
            if (dt <= 0) continue
            val bpm = srHz * 60 / dt
            if (bpm in MIN_BPM..MAX_BPM) intervals += dt
        }
        if (intervals.size < MIN_INTERVALS) return null
        intervals.sort()
        val median = intervals[intervals.size / 2]
        val consistent = intervals.filter { abs(it - median).toDouble() / median <= CONSISTENT_RR_FRACTION }
        if (consistent.size < MIN_CONSISTENT) return null
        val consistentMedian = consistent.sorted()[consistent.size / 2]
        return (srHz * 60) / consistentMedian
    }

    private fun movingAverage(values: FloatArray, width: Int): FloatArray {
        val out = FloatArray(values.size)
        var sum = 0.0
        for (index in values.indices) {
            sum += values[index]
            if (index >= width) sum -= values[index - width]
            out[index] = (sum / minOf(width, index + 1)).toFloat()
        }
        return out
    }

    private fun peakWidth(signed: FloatArray, index: Int): Int {
        val floor = signed[index] * 0.2f
        var width = 1
        var cursor = index - 1
        while (cursor >= 0 && signed[cursor] >= floor) {
            width++
            cursor--
        }
        cursor = index + 1
        while (cursor < signed.size && signed[cursor] >= floor) {
            width++
            cursor++
        }
        return width
    }

    private fun percentile(values: FloatArray, p: Float): Float {
        val copy = values.copyOf()
        copy.sort()
        val idx = ((copy.size - 1) * p).toInt().coerceIn(0, copy.lastIndex)
        return copy[idx]
    }

    private data class Estimate(val bpm: Int, val cv: Double)

    private const val MIN_SECONDS = 2.0
    private const val MIN_PEAKS = 4
    private const val MIN_INTERVALS = 3
    private const val MIN_CONSISTENT = 3
    private const val MIN_BPM = 40
    private const val MAX_BPM = 180
    private const val ECG_REFRACTORY_MS = 450
    private const val PPG_REFRACTORY_MS = 400
    private const val MIN_AMPLITUDE_MV = 0.12f
    private const val MIN_PPG_SWING = 50f
    private const val PEAK_FRACTION = 0.55f
    private const val MIN_PEAK_WIDTH = 5
    private const val CONSISTENT_RR_FRACTION = 0.20
}

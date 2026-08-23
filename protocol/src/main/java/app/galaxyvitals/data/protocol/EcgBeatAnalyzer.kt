package app.galaxyvitals.data.protocol

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class EcgBeatResult(
    val bpmMedian: Double?,
    val panTompkinsPeaks: IntArray,
    val hamiltonPeaks: IntArray,
    val matchedPeaks: IntArray,
    val agreement: Double,
    val reason: String,
)

object EcgBeatAnalyzer {
    private const val TARGET_HZ = 500
    private const val MATCH_TOLERANCE_SAMPLES = 40
    private const val MIN_AGREEMENT = 0.80

    fun analyze(parsed: ParsedEcgFile, prepared: PreparedRecording): EcgBeatResult {
        if (!prepared.quality.usableForAnalysis) {
            return unavailable("Signal quality is insufficient for ECG-derived BPM")
        }
        val cleanStarts = prepared.windows.map { it.startRelMs }.toSet()
        val panAll = ArrayList<Int>()
        val hamiltonAll = ArrayList<Int>()
        var targetOffset = 0
        val polarity = parsed.effectivePolarity()
        prepared.quality.segments.forEach { segment ->
            val oriented = FloatArray(segment.samples.size) { index ->
                segment.samples[index].valueMv * polarity
            }
            val resampled = EcgFounderPreprocess.resamplePolyphase(oriented, parsed.srHz, TARGET_HZ)
            val hasCleanWindow = cleanStarts.any { start ->
                start >= segment.startRelMs && start + EcgFounderPreprocess.WINDOW_MS <= segment.endRelMs + 2L
            }
            if (!hasCleanWindow) return@forEach
            val filtered = EcgFounderPreprocess.filterBandpass(resampled)
            detectPanTompkins(filtered, TARGET_HZ).forEach { panAll += it + targetOffset }
            detectHamilton(filtered, TARGET_HZ).forEach { hamiltonAll += it + targetOffset }
            targetOffset += resampled.size + TARGET_HZ * 3
        }
        val pan = panAll.toIntArray()
        val hamilton = hamiltonAll.toIntArray()
        val matched = matchPeaks(pan, hamilton, MATCH_TOLERANCE_SAMPLES)
        val denominator = max(pan.size, hamilton.size)
        val agreement = if (denominator == 0) 0.0 else matched.size.toDouble() / denominator
        if (matched.size < 5 || agreement < MIN_AGREEMENT) {
            return EcgBeatResult(
                null, pan, hamilton, matched, agreement,
                "R-peak detectors disagree",
            )
        }
        val rr = ArrayList<Double>(matched.size)
        for (index in 1 until matched.size) {
            val interval = (matched[index] - matched[index - 1]) * 1000.0 / TARGET_HZ
            if (interval in 273.0..2_000.0) rr += interval
        }
        rr.sort()
        if (rr.size < 4) return EcgBeatResult(null, pan, hamilton, matched, agreement, "Too few valid RR intervals")
        val medianRr = if (rr.size % 2 == 1) rr[rr.size / 2]
        else (rr[rr.size / 2 - 1] + rr[rr.size / 2]) / 2.0
        return EcgBeatResult(60_000.0 / medianRr, pan, hamilton, matched, agreement, "")
    }

    internal fun detectPanTompkins(signal: FloatArray, srHz: Int): IntArray {
        if (signal.size < srHz * 2) return IntArray(0)
        val derivative = FloatArray(signal.size)
        for (index in 2 until signal.size - 2) {
            derivative[index] = (
                -signal[index - 2] - 2f * signal[index - 1] +
                    2f * signal[index + 1] + signal[index + 2]
                ) / 8f
        }
        val squared = FloatArray(signal.size) { derivative[it] * derivative[it] }
        val integrated = movingAverage(squared, max(1, (0.15 * srHz).toInt()))
        return thresholdPeaks(integrated, signal, srHz, thresholdScale = 0.55, refractoryMs = 250)
    }

    internal fun detectHamilton(signal: FloatArray, srHz: Int): IntArray {
        if (signal.size < srHz * 2) return IntArray(0)
        val envelope = FloatArray(signal.size)
        for (index in 1 until signal.size) envelope[index] = abs(signal[index] - signal[index - 1])
        val integrated = movingAverage(envelope, max(1, (0.08 * srHz).toInt()))
        return thresholdPeaks(integrated, signal, srHz, thresholdScale = 0.40, refractoryMs = 220)
    }

    private fun thresholdPeaks(
        envelope: FloatArray,
        signal: FloatArray,
        srHz: Int,
        thresholdScale: Double,
        refractoryMs: Int,
    ): IntArray {
        val mean = envelope.average()
        var variance = 0.0
        envelope.forEach { variance += (it - mean) * (it - mean) }
        val threshold = mean + thresholdScale * sqrt(variance / envelope.size)
        val refractory = refractoryMs * srHz / 1000
        val refine = max(1, srHz / 10)
        val peaks = ArrayList<Int>()
        var index = 1
        while (index < envelope.lastIndex) {
            if (envelope[index] >= threshold &&
                envelope[index] >= envelope[index - 1] &&
                envelope[index] > envelope[index + 1]
            ) {
                val from = (index - refine).coerceAtLeast(0)
                val to = (index + refine).coerceAtMost(signal.lastIndex)
                var refined = from
                for (candidate in from..to) {
                    if (abs(signal[candidate]) > abs(signal[refined])) refined = candidate
                }
                if (peaks.isEmpty() || refined - peaks.last() >= refractory) peaks += refined
                else if (abs(signal[refined]) > abs(signal[peaks.last()])) peaks[peaks.lastIndex] = refined
                index += refractory / 2
            }
            index++
        }
        return peaks.toIntArray()
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

    private fun matchPeaks(first: IntArray, second: IntArray, tolerance: Int): IntArray {
        val matched = ArrayList<Int>()
        var j = 0
        first.forEach { peak ->
            while (j < second.size && second[j] < peak - tolerance) j++
            if (j < second.size && abs(second[j] - peak) <= tolerance) {
                matched += (peak + second[j]) / 2
                j++
            }
        }
        return matched.toIntArray()
    }

    private fun unavailable(reason: String) = EcgBeatResult(
        bpmMedian = null,
        panTompkinsPeaks = IntArray(0),
        hamiltonPeaks = IntArray(0),
        matchedPeaks = IntArray(0),
        agreement = 0.0,
        reason = reason,
    )
}

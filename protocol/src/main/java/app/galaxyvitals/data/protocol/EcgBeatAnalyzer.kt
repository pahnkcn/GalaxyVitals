package app.galaxyvitals.data.protocol

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class EcgBpmStatus {
    RELIABLE,
    INSUFFICIENT_DATA,
    LOW_QUALITY,
    DETECTOR_DISAGREEMENT,
}

data class EcgBeatResult(
    val status: EcgBpmStatus,
    val bpmMedian: Double?,
    val primaryPeaks: IntArray,
    val secondaryPeaks: IntArray,
    val matchedPeaks: IntArray,
    val bSqi: Double,
    val cleanDurationMs: Long,
    val reason: String,
)

object EcgBeatAnalyzer {
    private const val TARGET_HZ = EcgQrsFilter.TARGET_HZ
    private const val MATCH_TOLERANCE_MS = 150
    private const val REFINE_RADIUS_MS = 100
    private const val MIN_RR_MS = 333.0
    private const val MAX_RR_MS = 1_500.0
    private const val MIN_RR_COUNT = 4
    private const val MIN_BSQI = 0.80
    private const val PRIMARY_INTEGRATION_MS = 150
    private const val SECONDARY_INTEGRATION_MS = 80
    private const val PRIMARY_REFRACTORY_MS = 200
    private const val SECONDARY_REFRACTORY_MS = 220
    private const val TWAVE_MS = 360
    private const val SEARCHBACK_RR = 1.66
    private const val EWMA = 0.125
    private const val THRESHOLD_NOISE_WEIGHT = 0.25
    private const val LEARN_SECONDS = 2

    fun analyze(parsed: ParsedEcgFile): EcgBeatResult =
        analyze(parsed, EcgFounderPreprocess.prepare(parsed))

    fun analyze(parsed: ParsedEcgFile, prepared: PreparedRecording): EcgBeatResult {
        val cleanDurationMs = prepared.quality.cleanUnionMs
        if (parsed.srHz !in EcgFounderPreprocess.SUPPORTED_INPUT_HZ) {
            return emptyResult(
                EcgBpmStatus.LOW_QUALITY,
                "Signal quality is insufficient for ECG-derived BPM",
                cleanDurationMs,
            )
        }
        val enoughCoverage = prepared.quality.cleanWindowCount >= SignalQualityAnalyzer.MIN_CLEAN_WINDOWS &&
            prepared.quality.cleanUnionMs >= SignalQualityAnalyzer.MIN_CLEAN_UNION_MS
        if (!enoughCoverage) {
            return emptyResult(
                EcgBpmStatus.LOW_QUALITY,
                "Signal quality is insufficient for ECG-derived BPM",
                cleanDurationMs,
            )
        }
        val polarity = parsed.effectivePolarity()
        val primary = ArrayList<Int>()
        val secondary = ArrayList<Int>()
        val matched = ArrayList<Int>()
        val rrMs = ArrayList<Double>()
        prepared.quality.segments.forEach { segment ->
            if (segment.samples.size < 2) return@forEach
            val oriented = FloatArray(segment.samples.size) { index ->
                segment.samples[index].valueMv * polarity
            }
            val resampled = EcgFounderPreprocess.resamplePolyphase(oriented, parsed.srHz, TARGET_HZ)
            val local = detectOnResampled(resampled)
            val offset = (segment.startRelMs * TARGET_HZ / 1_000L).toInt()
            local.primary.forEach { primary += it + offset }
            local.secondary.forEach { secondary += it + offset }
            local.matched.forEach { matched += it + offset }
            rrMs += local.rrMs
        }
        return finish(
            primary = primary.toIntArray(),
            secondary = secondary.toIntArray(),
            matched = matched.toIntArray(),
            rrMs = rrMs,
            cleanDurationMs = cleanDurationMs,
            hideBpmOnDisagreement = true,
        )
    }

    fun analyzeWindow(samplesMv: FloatArray, srHz: Int, signFactor: Int): EcgBeatResult {
        val cleanDurationMs = if (srHz <= 0 || samplesMv.isEmpty()) {
            0L
        } else {
            samplesMv.size * 1_000L / srHz
        }
        if (srHz !in EcgFounderPreprocess.SUPPORTED_INPUT_HZ || samplesMv.size < 2) {
            return emptyResult(
                EcgBpmStatus.INSUFFICIENT_DATA,
                "Too few valid RR intervals",
                cleanDurationMs,
            )
        }
        val oriented = FloatArray(samplesMv.size) { samplesMv[it] * signFactor }
        val resampled = EcgFounderPreprocess.resamplePolyphase(oriented, srHz, TARGET_HZ)
        val local = detectOnResampled(resampled)
        return finish(
            primary = local.primary,
            secondary = local.secondary,
            matched = local.matched,
            rrMs = local.rrMs,
            cleanDurationMs = cleanDurationMs,
            hideBpmOnDisagreement = false,
        )
    }

    private fun detectOnResampled(oriented: FloatArray): SegmentDetections {
        if (oriented.size <= EcgQrsFilter.WARMUP_SAMPLES) {
            return SegmentDetections(IntArray(0), IntArray(0), IntArray(0), emptyList())
        }
        val filtered = EcgQrsFilter.filter(oriented)
        val start = EcgQrsFilter.WARMUP_SAMPLES
        val derivative = fivePointDerivative(filtered)
        val squared = FloatArray(filtered.size) { derivative[it] * derivative[it] }
        val primaryEnv = movingAverage(squared, samplesForMs(PRIMARY_INTEGRATION_MS))
        val absDeriv = FloatArray(filtered.size)
        for (index in 1 until filtered.size) {
            absDeriv[index] = abs(filtered[index] - filtered[index - 1])
        }
        val secondaryEnv = movingAverage(absDeriv, samplesForMs(SECONDARY_INTEGRATION_MS))
        val primaryRaw = detectPeaks(
            envelope = primaryEnv,
            startIndex = start,
            refractoryMs = PRIMARY_REFRACTORY_MS,
            twaveMs = TWAVE_MS,
            searchBack = true,
        )
        val secondaryRaw = detectPeaks(
            envelope = secondaryEnv,
            startIndex = start,
            refractoryMs = SECONDARY_REFRACTORY_MS,
            twaveMs = null,
            searchBack = false,
        )
        val primary = refinePeaks(
            delayCompensate(primaryRaw, samplesForMs(PRIMARY_INTEGRATION_MS)),
            oriented,
        )
        val secondary = refinePeaks(
            delayCompensate(secondaryRaw, samplesForMs(SECONDARY_INTEGRATION_MS)),
            oriented,
        )
        val matched = matchPeaks(primary, secondary, samplesForMs(MATCH_TOLERANCE_MS))
        return SegmentDetections(primary, secondary, matched, rrIntervals(matched))
    }

    private fun detectPeaks(
        envelope: FloatArray,
        startIndex: Int,
        refractoryMs: Int,
        twaveMs: Int?,
        searchBack: Boolean,
    ): IntArray {
        if (startIndex >= envelope.lastIndex) return IntArray(0)
        val refractory = samplesForMs(refractoryMs)
        val twave = twaveMs?.let(::samplesForMs) ?: 0
        val candidates = findLocalMaxima(envelope, startIndex)
        if (candidates.isEmpty()) return IntArray(0)

        val learnEnd = min(envelope.size, startIndex + LEARN_SECONDS * TARGET_HZ)
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
        fun threshold(): Double = npki + THRESHOLD_NOISE_WEIGHT * (spki - npki)

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
            spki = EWMA * value + (1.0 - EWMA) * spki
            if (spki < npki) spki = npki
        }

        fun trySearchBack(untilIndex: Int): Boolean {
            if (!searchBack || qrs.isEmpty()) return false
            val mean = meanRr()
            if (mean <= 0.0) return false
            val last = qrs.last()
            val limit = (SEARCHBACK_RR * mean).toInt()
            if (untilIndex - last <= limit) return false
            val from = last + refractory
            val to = min(untilIndex - refractory, last + limit)
            if (to <= from) return false
            val half = 0.5 * threshold()
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
                val last = qrs.lastOrNull()
                if (twave > 0 && last != null && index - last <= twave && qrsAmp.isNotEmpty()) {
                    val previous = qrsAmp.last()
                    val weakerThanQrs = value < 0.5 * previous
                    val shallowerSlope = abs(slope(envelope, index)) <= 0.5 * abs(slope(envelope, last))
                    if (weakerThanQrs || shallowerSlope) {
                        npki = EWMA * value + (1.0 - EWMA) * npki
                        cursor++
                        continue
                    }
                }
                accept(index, value)
            } else if (value > noiseThr) {
                npki = EWMA * value + (1.0 - EWMA) * npki
            }
            cursor++
        }
        if (searchBack && qrs.isNotEmpty()) {
            while (trySearchBack(envelope.size)) {
            }
        }
        return qrs.toIntArray()
    }

    private fun slope(envelope: FloatArray, peak: Int): Double {
        val width = samplesForMs(75)
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

    private fun delayCompensate(peaks: IntArray, delaySamples: Int): IntArray {
        if (delaySamples <= 0 || peaks.isEmpty()) return peaks
        val out = ArrayList<Int>(peaks.size)
        val seen = HashSet<Int>(peaks.size)
        for (peak in peaks) {
            val shifted = (peak - delaySamples).coerceAtLeast(0)
            if (seen.add(shifted)) out += shifted
        }
        return out.toIntArray()
    }

    private fun refinePeaks(peaks: IntArray, oriented: FloatArray): IntArray {
        if (peaks.isEmpty() || oriented.isEmpty()) return IntArray(0)
        val radius = samplesForMs(REFINE_RADIUS_MS)
        val refined = ArrayList<Int>(peaks.size)
        val seen = HashSet<Int>(peaks.size)
        for (peak in peaks) {
            val from = (peak - radius).coerceAtLeast(0)
            val to = (peak + radius).coerceAtMost(oriented.lastIndex)
            var best = from
            var bestVal = oriented[from]
            for (index in from..to) {
                if (oriented[index] > bestVal) {
                    bestVal = oriented[index]
                    best = index
                }
            }
            if (seen.add(best)) refined += best
        }
        refined.sort()
        return refined.toIntArray()
    }

    private fun matchPeaks(primary: IntArray, secondary: IntArray, tolerance: Int): IntArray {
        val matched = ArrayList<Int>()
        var j = 0
        for (peak in primary) {
            while (j < secondary.size && secondary[j] < peak - tolerance) j++
            if (j < secondary.size && abs(secondary[j] - peak) <= tolerance) {
                matched += (peak + secondary[j]) / 2
                j++
            }
        }
        return matched.toIntArray()
    }

    private fun rrIntervals(peaks: IntArray): List<Double> {
        if (peaks.size < 2) return emptyList()
        val rr = ArrayList<Double>(peaks.size - 1)
        for (index in 1 until peaks.size) {
            val interval = (peaks[index] - peaks[index - 1]) * 1_000.0 / TARGET_HZ
            if (interval in MIN_RR_MS..MAX_RR_MS) rr += interval
        }
        return rr
    }

    private fun finish(
        primary: IntArray,
        secondary: IntArray,
        matched: IntArray,
        rrMs: List<Double>,
        cleanDurationMs: Long,
        hideBpmOnDisagreement: Boolean,
    ): EcgBeatResult {
        val denominator = primary.size + secondary.size - matched.size
        val bSqi = if (denominator == 0) 0.0 else matched.size.toDouble() / denominator
        if (rrMs.size < MIN_RR_COUNT) {
            return EcgBeatResult(
                status = EcgBpmStatus.INSUFFICIENT_DATA,
                bpmMedian = null,
                primaryPeaks = primary,
                secondaryPeaks = secondary,
                matchedPeaks = matched,
                bSqi = bSqi,
                cleanDurationMs = cleanDurationMs,
                reason = "Too few valid RR intervals",
            )
        }
        val sorted = rrMs.sorted()
        val medianRr = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        val bpm = 60_000.0 / medianRr
        if (bSqi < MIN_BSQI) {
            return EcgBeatResult(
                status = EcgBpmStatus.DETECTOR_DISAGREEMENT,
                bpmMedian = if (hideBpmOnDisagreement) null else bpm,
                primaryPeaks = primary,
                secondaryPeaks = secondary,
                matchedPeaks = matched,
                bSqi = bSqi,
                cleanDurationMs = cleanDurationMs,
                reason = "R-peak detectors disagree",
            )
        }
        return EcgBeatResult(
            status = EcgBpmStatus.RELIABLE,
            bpmMedian = bpm,
            primaryPeaks = primary,
            secondaryPeaks = secondary,
            matchedPeaks = matched,
            bSqi = bSqi,
            cleanDurationMs = cleanDurationMs,
            reason = "",
        )
    }

    private fun emptyResult(status: EcgBpmStatus, reason: String, cleanDurationMs: Long) = EcgBeatResult(
        status = status,
        bpmMedian = null,
        primaryPeaks = IntArray(0),
        secondaryPeaks = IntArray(0),
        matchedPeaks = IntArray(0),
        bSqi = 0.0,
        cleanDurationMs = cleanDurationMs,
        reason = reason,
    )

    private fun samplesForMs(ms: Int): Int = max(1, TARGET_HZ * ms / 1_000)

    private data class SegmentDetections(
        val primary: IntArray,
        val secondary: IntArray,
        val matched: IntArray,
        val rrMs: List<Double>,
    )
}

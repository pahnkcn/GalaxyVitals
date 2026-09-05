package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.EcgSample
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class EcgBpmStatus {
    RELIABLE,
    INSUFFICIENT_DATA,
    LOW_QUALITY,
    DETECTOR_DISAGREEMENT,
}

/**
 * How the caller prepared the samples handed to the detector.
 *
 * Baseline wander on a wrist capture is larger than the QRS it sits under, so a
 * detector that measures peak position on raw samples measures the wander. The
 * detector therefore always runs on a 0.5-40 Hz, mains-free trace; this only
 * says who produced it.
 */
enum class EcgDetectorInput {
    /** Untouched sensor samples. The analyzer conditions them zero-phase. */
    RAW,

    /**
     * Already conditioned by the caller with a matched causal chain
     * ([EcgCausalConditioning]). Used by the on-watch live path, which filters
     * once as samples stream in instead of re-filtering every sliding window.
     */
    CONDITIONED,
}

/**
 * Beat-to-beat intervals split into the series used for rate and the series
 * used for HRV.
 *
 * [allMs] keeps every interval that is physiologically possible at all;
 * [nnMs] keeps only those that also survive the adaptive plausibility check
 * against the running median, which is the only series HRV may be built from.
 */
data class EcgRrSeries(
    val allMs: List<Double>,
    val nnMs: List<Double>,
    /**
     * Entry `i` is true when `nnMs[i]` and `nnMs[i - 1]` were adjacent in the
     * unfiltered series. Only those pairs may enter RMSSD or pNN50: a
     * successive difference taken across a removed artifact is not a successive
     * difference. Entry `0` is always false.
     */
    val nnSuccessive: List<Boolean>,
    /** Intervals about twice the running median: a beat was not detected. */
    val missedBeatCount: Int,
    /** Intervals about half the running median: something extra was detected. */
    val extraDetectionCount: Int,
    /** Everything else that failed the plausibility check. */
    val implausibleCount: Int,
    /** Intervals examined, including the ones that were rejected. */
    val candidateCount: Int,
) {
    val correctedCount: Int get() = missedBeatCount + extraDetectionCount + implausibleCount

    /** Share of candidate intervals excluded from [nnMs]. */
    val correctedFraction: Double
        get() = if (candidateCount == 0) 0.0 else correctedCount.toDouble() / candidateCount

    companion object {
        val EMPTY = EcgRrSeries(emptyList(), emptyList(), emptyList(), 0, 0, 0, 0)
    }
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
    val rr: EcgRrSeries = EcgRrSeries.EMPTY,
    /** Rate of the grid the peak indices are expressed on. */
    val analysisSrHz: Double = EcgFounderPreprocess.TARGET_HZ.toDouble(),
)

object EcgBeatAnalyzer {
    private const val TARGET_HZ = EcgQrsFilter.TARGET_HZ

    fun analyze(parsed: ParsedEcgFile): EcgBeatResult =
        analyze(parsed, EcgFounderPreprocess.prepare(parsed))

    fun analyze(
        parsed: ParsedEcgFile,
        prepared: PreparedRecording,
        config: EcgBeatDetectorConfig = EcgBeatDetectorConfig.DEFAULT,
    ): EcgBeatResult {
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
        val analysisSrHz = analysisSrHz(parsed.srHz, parsed.effectiveSrHz)
        val sourceSrHz = conditioningSrHz(parsed.srHz, parsed.effectiveSrHz)
        val primary = ArrayList<Int>()
        val secondary = ArrayList<Int>()
        val matched = ArrayList<Int>()
        val rrAll = ArrayList<Double>()
        val rrNn = ArrayList<Double>()
        val rrNnSuccessive = ArrayList<Boolean>()
        var missed = 0
        var extra = 0
        var implausible = 0
        var candidates = 0
        val envelopeSnrs = ArrayList<Double>()
        prepared.quality.segments.forEach { segment ->
            if (segment.samples.size < 2) return@forEach
            // Condition the whole continuous segment once: filtering each clean
            // range on its own would give every range its own edge transient.
            val conditioned = conditionSegment(segment.samples, polarity, sourceSrHz)
            prepared.cleanRanges.forEach { range ->
                val bounds = indexRange(segment.samples, range) ?: return@forEach
                if (bounds.last - bounds.first + 1 < 2) return@forEach
                val slice = FloatArray(bounds.last - bounds.first + 1) { index ->
                    conditioned[bounds.first + index].toFloat()
                }
                val resampled = EcgFounderPreprocess.resamplePolyphase(slice, parsed.srHz, TARGET_HZ)
                val local = detectWithOptionalDualPolarity(resampled, config, analysisSrHz)
                val offset = (segment.samples[bounds.first].relMs * TARGET_HZ / 1_000L).toInt()
                local.primary.forEach { primary += it + offset }
                local.secondary.forEach { secondary += it + offset }
                local.matched.forEach { matched += it + offset }
                rrAll += local.rr.allMs
                rrNn += local.rr.nnMs
                rrNnSuccessive += local.rr.nnSuccessive
                missed += local.rr.missedBeatCount
                extra += local.rr.extraDetectionCount
                implausible += local.rr.implausibleCount
                candidates += local.rr.candidateCount
                envelopeSnrs += local.envelopeSnr
            }
        }
        return finish(
            primary = primary.sortedToIntArray(),
            secondary = secondary.sortedToIntArray(),
            matched = matched.sortedToIntArray(),
            rr = EcgRrSeries(rrAll, rrNn, rrNnSuccessive, missed, extra, implausible, candidates),
            envelopeSnr = if (envelopeSnrs.isEmpty()) 0.0 else envelopeSnrs.median(),
            cleanDurationMs = cleanDurationMs,
            config = config,
            analysisSrHz = analysisSrHz,
        )
    }

    /**
     * Rate of the grid the detector runs on.
     *
     * Detection happens after [EcgFounderPreprocess.resamplePolyphase] has mapped
     * `srHz` index-for-index onto [TARGET_HZ], so the real spacing of those
     * samples is `TARGET_HZ * effectiveSrHz / srHz`. Galaxy Watch schema-v3 files
     * measure near 501.67 Hz rather than the declared 500, and using the declared
     * rate makes every RR interval - and therefore every reported BPM - 0.33% off.
     */
    internal fun analysisSrHz(srHz: Int, effectiveSrHz: Double): Double {
        if (srHz <= 0 || !effectiveSrHz.isFinite() || effectiveSrHz <= 0.0) return TARGET_HZ.toDouble()
        val rate = TARGET_HZ * effectiveSrHz / srHz
        val deviation = abs(rate / TARGET_HZ - 1.0)
        return if (deviation > EcgSignalChain.MAX_SR_DEVIATION) TARGET_HZ.toDouble() else rate
    }

    /** Rate the conditioning filters are designed for, before any resampling. */
    private fun conditioningSrHz(srHz: Int, effectiveSrHz: Double): Double {
        if (srHz <= 0) return TARGET_HZ.toDouble()
        if (!effectiveSrHz.isFinite() || effectiveSrHz <= 0.0) return srHz.toDouble()
        val deviation = abs(effectiveSrHz / srHz - 1.0)
        return if (deviation > EcgSignalChain.MAX_SR_DEVIATION) srHz.toDouble() else effectiveSrHz
    }

    fun analyzeWindow(samplesMv: FloatArray, srHz: Int, signFactor: Int): EcgBeatResult =
        analyzeWindow(samplesMv, srHz, signFactor, EcgBeatDetectorConfig.DEFAULT)

    fun analyzeWindow(
        samplesMv: FloatArray,
        srHz: Int,
        signFactor: Int,
        config: EcgBeatDetectorConfig,
        effectiveSrHz: Double = srHz.toDouble(),
        input: EcgDetectorInput = EcgDetectorInput.RAW,
    ): EcgBeatResult {
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
        val conditioned = when (input) {
            EcgDetectorInput.CONDITIONED -> oriented
            EcgDetectorInput.RAW -> conditionWindow(
                oriented,
                conditioningSrHz(srHz, effectiveSrHz),
            )
        }
        val resampled = EcgFounderPreprocess.resamplePolyphase(conditioned, srHz, TARGET_HZ)
        val analysisSrHz = analysisSrHz(srHz, effectiveSrHz)
        val local = detectWithOptionalDualPolarity(resampled, config, analysisSrHz)
        return finish(
            primary = local.primary,
            secondary = local.secondary,
            matched = local.matched,
            rr = local.rr,
            envelopeSnr = local.envelopeSnr,
            cleanDurationMs = cleanDurationMs,
            config = config,
            analysisSrHz = analysisSrHz,
        )
    }

    // ------------------------------------------------------------ conditioning

    private fun conditionSegment(
        samples: List<EcgSample>,
        polarity: Float,
        srHz: Double,
    ): DoubleArray {
        val oriented = DoubleArray(samples.size) { samples[it].valueMv * polarity.toDouble() }
        return conditionZeroPhase(oriented, srHz)
    }

    private fun conditionWindow(oriented: FloatArray, srHz: Double): FloatArray {
        val values = DoubleArray(oriented.size) { oriented[it].toDouble() }
        val filtered = conditionZeroPhase(values, srHz)
        return FloatArray(filtered.size) { filtered[it].toFloat() }
    }

    /**
     * Mains removal, median-cascade baseline removal and a 40 Hz zero-phase
     * low-pass - [EcgSignalChain]'s own chain, which the offline quality stage
     * already used while the detector was still reading raw samples.
     *
     * The 40 Hz cutoff is monitoring bandwidth and must not feed an amplitude or
     * morphology measurement; here it only locates R peaks in time, where the
     * flatter baseline is worth far more than the lost high-frequency content.
     */
    private fun conditionZeroPhase(values: DoubleArray, srHz: Double): DoubleArray {
        if (values.isEmpty() || srHz <= 0.0) return values
        val line = EcgSignalChain.estimateLineNoise(values, srHz)
        return EcgSignalChain.filter(values, srHz, EcgBandwidth.MONITOR, line)
    }

    /** Inclusive index bounds of the samples whose `relMs` falls inside [range]. */
    private fun indexRange(samples: List<EcgSample>, range: LongRange): IntRange? {
        if (samples.isEmpty()) return null
        if (samples.last().relMs < range.first || samples.first().relMs > range.last) return null
        var from = -1
        var to = -1
        for (index in samples.indices) {
            val relMs = samples[index].relMs
            if (relMs < range.first) continue
            if (relMs > range.last) break
            if (from < 0) from = index
            to = index
        }
        return if (from < 0) null else from..to
    }

    // ---------------------------------------------------------------- detection

    private fun detectWithOptionalDualPolarity(
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

    /**
     * Undo the group delay of the trailing moving average in [movingAverage].
     *
     * A trailing average of width `W` delays by `(W-1)/2`, not by `W`. Shifting
     * by the full window put the 150 ms primary envelope and the 80 ms secondary
     * envelope ~35 ms apart before any noise, which spent the whole match budget
     * and depressed bSqi on clean recordings.
     *
     * [filterDelaySamples] carries the rest of the pipeline's delay - forward-only
     * band-pass filtering is not phase-linear - so the compensated index lands on
     * the R wave itself rather than 70 ms past it.
     */
    internal fun delayCompensate(
        peaks: IntArray,
        windowWidth: Int,
        filterDelaySamples: Double = 0.0,
    ): IntArray {
        if (peaks.isEmpty()) return peaks
        val delay = (max(0, windowWidth - 1) / 2.0 + filterDelaySamples).roundToInt()
        if (delay <= 0) return peaks
        return IntArray(peaks.size) { (peaks[it] - delay).coerceAtLeast(0) }
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
    private fun refinePeaks(
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

    private fun matchPeaks(
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

    private fun finish(
        primary: IntArray,
        secondary: IntArray,
        matched: IntArray,
        rr: EcgRrSeries,
        envelopeSnr: Double,
        cleanDurationMs: Long,
        config: EcgBeatDetectorConfig,
        analysisSrHz: Double,
    ): EcgBeatResult {
        val denominator = primary.size + secondary.size - matched.size
        val bSqi = if (denominator == 0) 0.0 else matched.size.toDouble() / denominator
        if (rr.nnMs.size < config.minRrCount) {
            return EcgBeatResult(
                status = EcgBpmStatus.INSUFFICIENT_DATA,
                bpmMedian = null,
                primaryPeaks = primary,
                secondaryPeaks = secondary,
                matchedPeaks = matched,
                bSqi = bSqi,
                cleanDurationMs = cleanDurationMs,
                reason = "Too few valid RR intervals",
                rr = rr,
                analysisSrHz = analysisSrHz,
            )
        }
        val bpm = 60_000.0 / rr.nnMs.median()
        if (bSqi < config.minBsqi) {
            return EcgBeatResult(
                status = EcgBpmStatus.DETECTOR_DISAGREEMENT,
                bpmMedian = null,
                primaryPeaks = primary,
                secondaryPeaks = secondary,
                matchedPeaks = matched,
                bSqi = bSqi,
                cleanDurationMs = cleanDurationMs,
                reason = "R-peak detectors disagree",
                rr = rr,
                analysisSrHz = analysisSrHz,
            )
        }
        if (envelopeSnr < config.minEnvelopeSnr && bSqi < config.snrBypassBsqi) {
            return EcgBeatResult(
                status = EcgBpmStatus.LOW_QUALITY,
                bpmMedian = null,
                primaryPeaks = primary,
                secondaryPeaks = secondary,
                matchedPeaks = matched,
                bSqi = bSqi,
                cleanDurationMs = cleanDurationMs,
                reason = "Envelope SNR is insufficient",
                rr = rr,
                analysisSrHz = analysisSrHz,
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
            rr = rr,
            analysisSrHz = analysisSrHz,
        )
    }

    /** Median with the detector's convention: no beats means no rate, not zero. */
    private fun List<Double>.median(): Double = EcgStats.median(this, whenEmpty = Double.NaN)

    private fun List<Int>.sortedToIntArray(): IntArray {
        val out = toIntArray()
        out.sort()
        return out
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

    /**
     * Duration in samples of the grid the detector runs on.
     *
     * Uses the measured [analysisSrHz] rather than the declared 500, so
     * refractory periods, T-wave windows, integration widths and the match
     * tolerance all mean what they say on a watch whose clock runs 0.33% fast.
     */
    internal fun samplesForMs(ms: Int, analysisSrHz: Double): Int {
        val rate = if (analysisSrHz.isFinite() && analysisSrHz > 0.0) analysisSrHz else TARGET_HZ.toDouble()
        return max(1, (ms * rate / 1_000.0).roundToInt())
    }

    private data class PeakDetection(
        val indices: IntArray,
        val signalNoise: Double,
    )

    private class RefinedPeaks(
        val indices: IntArray,
        val positions: DoubleArray,
    ) {
        companion object {
            val EMPTY = RefinedPeaks(IntArray(0), DoubleArray(0))
        }
    }

    private data class SegmentDetections(
        val primary: IntArray,
        val secondary: IntArray,
        val matched: IntArray,
        val rr: EcgRrSeries,
        val envelopeSnr: Double,
        val dominantDeflection: Double,
    )
}

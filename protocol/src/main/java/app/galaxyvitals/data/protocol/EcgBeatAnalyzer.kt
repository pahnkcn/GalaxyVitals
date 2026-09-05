package app.galaxyvitals.data.protocol

import app.galaxyvitals.data.protocol.beat.detectWithOptionalDualPolarity
import app.galaxyvitals.domain.EcgSample
import kotlin.math.abs

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

/**
 * ECG-derived beat rate for a recording or a live window.
 *
 * The detector itself lives in `beat/`: envelope detection, peak refinement and
 * the RR split are each their own problem and each has its own file. What is
 * here is the part that decides whether the answer may be reported at all -
 * which segments are clean enough to look at, how they are conditioned, and the
 * quality gates a rate has to clear before it becomes a number on screen.
 */
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

    // The detector's own entry points, re-exported so that everything about a
    // beat is reachable through one door.

    internal fun delayCompensate(
        peaks: IntArray,
        windowWidth: Int,
        filterDelaySamples: Double = 0.0,
    ): IntArray = app.galaxyvitals.data.protocol.beat.delayCompensate(
        peaks,
        windowWidth,
        filterDelaySamples,
    )

    internal fun subSamplePeak(signal: FloatArray, index: Int): Double =
        app.galaxyvitals.data.protocol.beat.subSamplePeak(signal, index)

    internal fun rrSeries(
        positions: DoubleArray,
        config: EcgBeatDetectorConfig,
        analysisSrHz: Double,
    ): EcgRrSeries = app.galaxyvitals.data.protocol.beat.rrSeries(positions, config, analysisSrHz)
}

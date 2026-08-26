package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.domain.EcgSampleFlags
import app.galaxyvitals.domain.SignalQualityStatus
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

enum class QualityFlag {
    LEGACY_TIMING,
    UNSUPPORTED_RATE,
    TIMESTAMP_GAP,
    MISSING_SAMPLES,
    CONTACT_LOSS,
    CLIPPING,
    FLATLINE,
    HELD_SIGNAL,
    IMPULSE_NOISE,
    BASELINE_DRIFT,
    MAINS_INTERFERENCE,
    HIGH_FREQUENCY_NOISE,
    LOW_AMPLITUDE,
    INSUFFICIENT_CLEAN_COVERAGE,
}

data class ContinuousSegment(
    val samples: List<EcgSample>,
    val startRelMs: Long,
    val endRelMs: Long,
)

data class SignalQualityReport(
    val status: SignalQualityStatus,
    val flags: Set<QualityFlag>,
    val effectiveHz: Double,
    val gapCount: Int,
    val missingSampleCount: Int,
    val clippedSampleCount: Int,
    val longestFlatRunMs: Long,
    val cleanCoveragePct: Double,
    val cleanUnionMs: Long,
    val cleanWindowCount: Int,
    val segments: List<ContinuousSegment>,
    val cleanRanges: List<LongRange> = emptyList(),
) {
    val usableForAnalysis: Boolean
        get() = status == SignalQualityStatus.GOOD &&
            cleanWindowCount >= SignalQualityAnalyzer.MIN_CLEAN_WINDOWS &&
            cleanUnionMs >= SignalQualityAnalyzer.MIN_CLEAN_UNION_MS

    fun flagsJson(): String = flags.sortedBy(Enum<*>::name)
        .joinToString(prefix = "[", postfix = "]") { "\"${it.name}\"" }
}

object SignalQualityAnalyzer {
    const val MIN_CLEAN_WINDOWS = 3
    const val MIN_CLEAN_UNION_MS = 20_000L

    fun analyze(parsed: ParsedEcgFile): SignalQualityReport {
        val samples = parsed.samples
        if (samples.size < 2) {
            return SignalQualityReport(
                SignalQualityStatus.INVALID,
                setOf(QualityFlag.INSUFFICIENT_CLEAN_COVERAGE),
                0.0, 0, 0, 0, 0L, 0.0, 0L, 0, emptyList(),
            )
        }
        val expectedMs = 1000.0 / parsed.srHz
        val flags = linkedSetOf<QualityFlag>()
        val timingTrust = parsed.timingTrust
        if (
            timingTrust == app.galaxyvitals.domain.TimingTrust.ASSUMED ||
            timingTrust == app.galaxyvitals.domain.TimingTrust.UNVERIFIED
        ) {
            flags += QualityFlag.LEGACY_TIMING
        }
        if (parsed.srHz !in setOf(250, 300, 500)) flags += QualityFlag.UNSUPPORTED_RATE

        var gaps = parsed.sequenceGapCount
        var missing = parsed.missingSampleCount + parsed.sequenceGapCount
        if (timingTrust != app.galaxyvitals.domain.TimingTrust.SEQUENCE_RECONSTRUCTED) {
            gaps += parsed.gapCount
        }
        var clipped = parsed.clippedSampleCount
        val segments = ArrayList<ContinuousSegment>()
        var start = 0
        for (index in 1 until samples.size) {
            val flaggedGap = samples[index].flags and
                (EcgSampleFlags.TIMESTAMP_GAP or EcgSampleFlags.SEQUENCE_GAP) != 0
            if (flaggedGap) {
                gaps++
                missing++
                addSegment(samples, start, index - 1, segments)
                start = index
            }
        }
        addSegment(samples, start, samples.lastIndex, segments)
        if (gaps > 0) flags += QualityFlag.TIMESTAMP_GAP
        if (missing > 0) flags += QualityFlag.MISSING_SAMPLES

        val values = FloatArray(samples.size) { samples[it].valueMv }
        val acquisitionContact = parsed.contactLossCount + samples.count {
            it.flags and EcgSampleFlags.CONTACT_LOSS != 0
        }
        if (acquisitionContact > 0) flags += QualityFlag.CONTACT_LOSS
        clipped += samples.count { sample ->
            sample.flags and EcgSampleFlags.CLIPPED != 0 ||
                parsed.minThresholdMv?.let { sample.valueMv < it } == true ||
                parsed.maxThresholdMv?.let { sample.valueMv > it } == true
        }
        if (clipped > 0) flags += QualityFlag.CLIPPING

        val longestFlatSamples = longestFlatRun(values)
        val longestFlatMs = (longestFlatSamples * expectedMs).toLong()
        if (longestFlatMs >= 1_000L) flags += QualityFlag.FLATLINE
        else if (longestFlatMs >= 400L) flags += QualityFlag.HELD_SIGNAL

        flags += spectralAndMorphologyFlags(values, parsed.srHz)
        val effectiveHz = if (samples.last().relMs > samples.first().relMs) {
            (samples.size - 1) * 1000.0 / (samples.last().relMs - samples.first().relMs)
        } else 0.0
        if (abs(effectiveHz - parsed.srHz) / parsed.srHz > 0.01) {
            flags += QualityFlag.MISSING_SAMPLES
        }

        val fatal = setOf(
            QualityFlag.UNSUPPORTED_RATE,
            QualityFlag.TIMESTAMP_GAP,
            QualityFlag.MISSING_SAMPLES,
            QualityFlag.CONTACT_LOSS,
            QualityFlag.CLIPPING,
            QualityFlag.FLATLINE,
            QualityFlag.HELD_SIGNAL,
            QualityFlag.IMPULSE_NOISE,
            QualityFlag.LOW_AMPLITUDE,
        )
        val status = if (flags.any { it in fatal }) {
            SignalQualityStatus.LOW_QUALITY
        } else {
            SignalQualityStatus.GOOD
        }
        return SignalQualityReport(
            status = status,
            flags = flags,
            effectiveHz = effectiveHz,
            gapCount = gaps,
            missingSampleCount = missing,
            clippedSampleCount = clipped,
            longestFlatRunMs = longestFlatMs,
            cleanCoveragePct = if (status == SignalQualityStatus.GOOD) 100.0 else 0.0,
            cleanUnionMs = 0L,
            cleanWindowCount = 0,
            segments = segments,
        )
    }

    internal fun assessWindow(values: FloatArray, srHz: Int): Set<QualityFlag> {
        val flags = linkedSetOf<QualityFlag>()
        val flatMs = (longestFlatRun(values) * 1000L) / srHz
        if (flatMs >= 1_000L) flags += QualityFlag.FLATLINE
        else if (flatMs >= 400L) flags += QualityFlag.HELD_SIGNAL
        flags += spectralAndMorphologyFlags(values, srHz)
        return flags
    }

    internal fun mergeRanges(ranges: List<LongRange>): List<LongRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy(LongRange::first)
        val merged = ArrayList<LongRange>(sorted.size)
        var start = sorted.first().first
        var end = sorted.first().last
        for (index in 1 until sorted.size) {
            val range = sorted[index]
            if (range.first <= end) {
                end = max(end, range.last)
            } else {
                merged += start..end
                start = range.first
                end = range.last
            }
        }
        merged += start..end
        return merged
    }

    internal fun withCleanWindows(
        report: SignalQualityReport,
        hopWindows: List<LongRange>,
        recordingDurationMs: Long,
        mergedRanges: List<LongRange> = mergeRanges(hopWindows),
    ): SignalQualityReport {
        if (hopWindows.isEmpty()) {
            return report.copy(
                status = SignalQualityStatus.LOW_QUALITY,
                flags = report.flags + QualityFlag.INSUFFICIENT_CLEAN_COVERAGE,
                cleanRanges = emptyList(),
            )
        }
        val stored = if (mergedRanges.isEmpty()) mergeRanges(hopWindows) else mergedRanges
        val union = mergeRanges(stored).sumOf { it.last - it.first }
        val enough = hopWindows.size >= MIN_CLEAN_WINDOWS && union >= MIN_CLEAN_UNION_MS
        val newFlags = if (enough) report.flags else report.flags + QualityFlag.INSUFFICIENT_CLEAN_COVERAGE
        val fatalWithoutCoverage = newFlags - QualityFlag.LEGACY_TIMING -
            QualityFlag.BASELINE_DRIFT - QualityFlag.MAINS_INTERFERENCE - QualityFlag.HIGH_FREQUENCY_NOISE
        val status = if (enough && fatalWithoutCoverage.isEmpty()) {
            SignalQualityStatus.GOOD
        } else {
            SignalQualityStatus.LOW_QUALITY
        }
        return report.copy(
            status = status,
            flags = newFlags,
            cleanCoveragePct = if (recordingDurationMs > 0) {
                (union * 100.0 / recordingDurationMs).coerceIn(0.0, 100.0)
            } else 0.0,
            cleanUnionMs = union,
            cleanWindowCount = hopWindows.size,
            cleanRanges = stored,
        )
    }

    private fun addSegment(
        samples: List<EcgSample>,
        start: Int,
        end: Int,
        out: MutableList<ContinuousSegment>,
    ) {
        if (end < start) return
        val slice = samples.subList(start, end + 1)
        out += ContinuousSegment(slice, slice.first().relMs, slice.last().relMs)
    }

    private fun longestFlatRun(values: FloatArray): Int {
        if (values.isEmpty()) return 0
        var longest = 1
        var run = 1
        for (index in 1 until values.size) {
            if (abs(values[index] - values[index - 1]) <= 1e-5f) run++ else run = 1
            if (run > longest) longest = run
        }
        return longest
    }

    private fun spectralAndMorphologyFlags(values: FloatArray, srHz: Int): Set<QualityFlag> {
        if (values.size < max(16, srHz)) return setOf(QualityFlag.LOW_AMPLITUDE)
        val flags = linkedSetOf<QualityFlag>()
        val mean = values.average()
        var variance = 0.0
        var diffEnergy = 0.0
        var impulses = 0
        for (index in values.indices) {
            val centered = values[index] - mean
            variance += centered * centered
            if (index > 0) {
                val diff = values[index] - values[index - 1]
                diffEnergy += diff * diff
                if (abs(diff) > 3.0) impulses++
            }
        }
        variance /= values.size
        if (sqrt(variance) < 0.01) flags += QualityFlag.LOW_AMPLITUDE
        if (impulses > max(1, values.size / 1000)) flags += QualityFlag.IMPULSE_NOISE
        if (variance > 1e-10 && diffEnergy / values.size / variance > 1.2) {
            flags += QualityFlag.HIGH_FREQUENCY_NOISE
        }
        val half = values.size / 2
        val firstMean = values.take(half).average()
        val secondMean = values.drop(half).average()
        if (abs(firstMean - secondMean) > 0.5) flags += QualityFlag.BASELINE_DRIFT
        if (variance > 1e-10) {
            val mainsPower = max(goertzel(values, srHz, 50.0), goertzel(values, srHz, 60.0))
            if (mainsPower / (variance * values.size * values.size) > 0.08) {
                flags += QualityFlag.MAINS_INTERFERENCE
            }
        }
        return flags
    }

    private fun goertzel(values: FloatArray, srHz: Int, frequencyHz: Double): Double {
        if (frequencyHz >= srHz / 2.0) return 0.0
        val coefficient = 2.0 * cos(2.0 * PI * frequencyHz / srHz)
        var s1 = 0.0
        var s2 = 0.0
        values.forEach { value ->
            val s0 = value + coefficient * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - coefficient * s1 * s2
    }
}

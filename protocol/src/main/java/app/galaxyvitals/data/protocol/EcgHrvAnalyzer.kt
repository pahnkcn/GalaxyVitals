package app.galaxyvitals.data.protocol

import kotlin.math.abs
import kotlin.math.sqrt

enum class EcgHrvStatus {
    RELIABLE,

    /** Too few normal-to-normal intervals, or too little clean signal. */
    INSUFFICIENT_DATA,

    /** Enough intervals, but too many of them had to be corrected out. */
    TOO_MANY_CORRECTIONS,

    /** The beat detector itself did not produce a usable rate. */
    LOW_QUALITY,
}

/**
 * Time-domain HRV over one recording.
 *
 * Every number is built from the artifact-filtered `rrNN` series, never from
 * `rrAll`: one missed beat inflates RMSSD by more than any real change this is
 * meant to detect. [correctedRrFraction] and [coveragePct] are reported beside
 * the values so a caller can see how much of the record survived.
 */
data class EcgHrvResult(
    val status: EcgHrvStatus,
    val reason: String,
    val meanHrBpm: Double?,
    val sdnnMs: Double?,
    val rmssdMs: Double?,
    val pnn50Pct: Double?,
    val nnCount: Int,
    /** Successive NN pairs that were adjacent before artifact filtering. */
    val successivePairCount: Int,
    val nnDurationMs: Long,
    val analysedDurationMs: Long,
    /** Share of the analysed duration actually spanned by accepted intervals. */
    val coveragePct: Double,
    val correctedRrFraction: Double,
)

/**
 * Time-domain heart-rate variability from a completed beat analysis.
 *
 * **No frequency-domain output.** A 30 s strip is shorter than a single LF
 * cycle, so LF, HF and LF/HF over these recordings would be arithmetic without
 * meaning; they are deliberately not computed rather than computed and
 * disclaimed.
 */
object EcgHrvAnalyzer {
    /** Roughly 20 beats: below this SDNN is dominated by its own sampling error. */
    const val MIN_NN_COUNT = 20

    /** Successive-difference measures need their own minimum. */
    const val MIN_SUCCESSIVE_PAIRS = 10

    const val MIN_ANALYSED_DURATION_MS = 20_000L

    /** Above this share of corrected intervals the record is not worth reporting. */
    const val MAX_CORRECTED_FRACTION = 0.20

    /** Accepted intervals must span at least this much of the analysed window. */
    const val MIN_COVERAGE_PCT = 70.0

    const val PNN_THRESHOLD_MS = 50.0

    fun analyze(beat: EcgBeatResult): EcgHrvResult =
        analyze(beat.rr, beat.cleanDurationMs, beat.status)

    fun analyze(
        rr: EcgRrSeries,
        analysedDurationMs: Long,
        beatStatus: EcgBpmStatus = EcgBpmStatus.RELIABLE,
    ): EcgHrvResult {
        val nn = rr.nnMs
        val nnDurationMs = nn.sum().toLong()
        val coveragePct = if (analysedDurationMs <= 0L) {
            0.0
        } else {
            nnDurationMs * 100.0 / analysedDurationMs
        }
        fun abstain(status: EcgHrvStatus, reason: String) = EcgHrvResult(
            status = status,
            reason = reason,
            meanHrBpm = null,
            sdnnMs = null,
            rmssdMs = null,
            pnn50Pct = null,
            nnCount = nn.size,
            successivePairCount = successivePairCount(rr),
            nnDurationMs = nnDurationMs,
            analysedDurationMs = analysedDurationMs,
            coveragePct = coveragePct,
            correctedRrFraction = rr.correctedFraction,
        )

        if (beatStatus != EcgBpmStatus.RELIABLE) {
            return abstain(EcgHrvStatus.LOW_QUALITY, "Beat detection did not produce a reliable rate")
        }
        if (analysedDurationMs < MIN_ANALYSED_DURATION_MS) {
            return abstain(EcgHrvStatus.INSUFFICIENT_DATA, "Clean recording is shorter than 20 s")
        }
        if (nn.size < MIN_NN_COUNT) {
            return abstain(EcgHrvStatus.INSUFFICIENT_DATA, "Fewer than $MIN_NN_COUNT normal-to-normal intervals")
        }
        // Corrections first: a record full of them will also cover badly, and
        // "too many artifacts" is the more actionable of the two answers.
        if (rr.correctedFraction > MAX_CORRECTED_FRACTION) {
            return abstain(EcgHrvStatus.TOO_MANY_CORRECTIONS, "Too many RR intervals had to be corrected")
        }
        if (coveragePct < MIN_COVERAGE_PCT) {
            return abstain(EcgHrvStatus.INSUFFICIENT_DATA, "Accepted intervals cover too little of the recording")
        }

        val differences = successiveDifferences(rr)
        if (differences.size < MIN_SUCCESSIVE_PAIRS) {
            return abstain(
                EcgHrvStatus.INSUFFICIENT_DATA,
                "Fewer than $MIN_SUCCESSIVE_PAIRS uninterrupted NN pairs",
            )
        }

        val mean = nn.average()
        var sumSquaredDeviation = 0.0
        for (interval in nn) {
            val deviation = interval - mean
            sumSquaredDeviation += deviation * deviation
        }
        // Sample standard deviation: the recording is a sample of the rhythm,
        // not the whole of it.
        val sdnn = sqrt(sumSquaredDeviation / (nn.size - 1))

        var sumSquaredDifference = 0.0
        var overThreshold = 0
        for (difference in differences) {
            sumSquaredDifference += difference * difference
            if (abs(difference) > PNN_THRESHOLD_MS) overThreshold++
        }
        val rmssd = sqrt(sumSquaredDifference / differences.size)

        return EcgHrvResult(
            status = EcgHrvStatus.RELIABLE,
            reason = "",
            meanHrBpm = 60_000.0 / mean,
            sdnnMs = sdnn,
            rmssdMs = rmssd,
            pnn50Pct = overThreshold * 100.0 / differences.size,
            nnCount = nn.size,
            successivePairCount = differences.size,
            nnDurationMs = nnDurationMs,
            analysedDurationMs = analysedDurationMs,
            coveragePct = coveragePct,
            correctedRrFraction = rr.correctedFraction,
        )
    }

    private fun successiveDifferences(rr: EcgRrSeries): DoubleArray {
        val out = ArrayList<Double>(rr.nnMs.size)
        for (index in 1 until rr.nnMs.size) {
            if (rr.nnSuccessive.getOrElse(index) { false }) {
                out += rr.nnMs[index] - rr.nnMs[index - 1]
            }
        }
        return out.toDoubleArray()
    }

    private fun successivePairCount(rr: EcgRrSeries): Int {
        var count = 0
        for (index in 1 until rr.nnMs.size) {
            if (rr.nnSuccessive.getOrElse(index) { false }) count++
        }
        return count
    }
}

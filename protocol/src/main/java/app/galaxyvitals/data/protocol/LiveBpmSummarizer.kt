package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.LiveBpmObservation
import app.galaxyvitals.domain.LiveBpmSummary
import kotlin.math.min

object LiveBpmSummarizer {
    const val ALGORITHM_ID = "app.galaxyvitals.live_bpm.v1"
    const val SAMSUNG_PRIMARY_ALGORITHM_ID =
        "app.galaxyvitals.samsung_hr_primary_with_ecg_fallback.v1"
    const val SOURCE_SAMSUNG_HEART_RATE_CONTINUOUS = "SAMSUNG_HEART_RATE_CONTINUOUS"
    const val MAX_OBSERVATIONS = 64
    const val MAX_IBI_PER_OBSERVATION = 4
    const val STALE_AGE_MS = 3_000L
    const val RELIABLE = "RELIABLE"

    fun requireValid(observations: List<LiveBpmObservation>) {
        val error = validationError(observations) ?: return
        throw IllegalArgumentException(error)
    }

    fun parseValid(observations: List<LiveBpmObservation>) {
        val error = validationError(observations) ?: return
        throw EcgParseException(error)
    }

    fun validationError(observations: List<LiveBpmObservation>): String? {
        if (observations.size > MAX_OBSERVATIONS) {
            return "ECG contains more than $MAX_OBSERVATIONS live BPM observations"
        }
        var previousElapsed = Long.MIN_VALUE
        observations.forEach { observation ->
            if (observation.observedCaptureElapsedMs < previousElapsed) {
                return "Live BPM elapsed time must not go backwards"
            }
            previousElapsed = observation.observedCaptureElapsedMs
            if (observation.sensorTimestampMs?.let { it < 0L } == true) {
                return "Live BPM sensor timestamp must be nonnegative"
            }
            if (observation.ibiMs.size != observation.ibiStatus.size) {
                return "Live BPM IBI values and statuses must have equal sizes"
            }
            if (observation.ibiMs.size > MAX_IBI_PER_OBSERVATION) {
                return "Live BPM contains more than $MAX_IBI_PER_OBSERVATION IBI values"
            }
            if (observation.ibiMs.any { it < 0 }) {
                return "Live BPM IBI values must be nonnegative"
            }
            if (observation.status == RELIABLE) {
                val bpm = observation.displayedBpm
                if (bpm == null || !bpm.isFinite()) {
                    return "RELIABLE live BPM requires displayed BPM"
                }
                if (observation.source.isNullOrBlank()) {
                    return "RELIABLE live BPM requires source"
                }
                if (observation.source == SOURCE_SAMSUNG_HEART_RATE_CONTINUOUS) {
                    if (observation.sensorTimestampMs == null) {
                        return "RELIABLE Samsung heart rate requires sensor timestamp"
                    }
                    if (observation.sensorStatus != 1) {
                        return "RELIABLE Samsung heart rate requires successful sensor status"
                    }
                    if (bpm <= 0.0) {
                        return "RELIABLE Samsung heart rate requires positive BPM"
                    }
                } else {
                    val sqi = observation.bSqi
                    if (sqi == null || !sqi.isFinite()) {
                        return "RELIABLE live BPM requires bSQI"
                    }
                    val rr = observation.rrCount
                    if (rr == null || rr < 0) {
                        return "RELIABLE live BPM requires RR count"
                    }
                }
            }
        }
        return null
    }

    fun summarize(
        observations: List<LiveBpmObservation>,
        sessionDurationMs: Long,
    ): LiveBpmSummary {
        if (sessionDurationMs <= 0L) {
            return LiveBpmSummary(
                observationCount = observations.size,
                algorithmId = ALGORITHM_ID.takeIf { observations.isNotEmpty() },
            )
        }
        val ordered = observations.sortedBy(LiveBpmObservation::observedCaptureElapsedMs)
        val weighted = ArrayList<Pair<Double, Long>>(ordered.size)
        ordered.forEachIndexed { index, observation ->
            if (observation.status != RELIABLE) return@forEachIndexed
            val bpm = observation.displayedBpm ?: return@forEachIndexed
            if (!bpm.isFinite() || observation.estimateAgeMs > STALE_AGE_MS) return@forEachIndexed
            val start = observation.observedCaptureElapsedMs.coerceAtLeast(0L)
            val remainingTtl = (STALE_AGE_MS - observation.estimateAgeMs).coerceAtLeast(0L)
            val staleAt = start + remainingTtl
            val nextAt = ordered.getOrNull(index + 1)?.observedCaptureElapsedMs ?: sessionDurationMs
            val end = min(min(staleAt, nextAt), sessionDurationMs)
            val duration = (end - start).coerceAtLeast(0L)
            if (duration > 0L) weighted += bpm to duration
        }
        val covered = weighted.sumOf { it.second }
        val values = weighted.map { it.first }
        return LiveBpmSummary(
            median = weightedMedian(weighted),
            min = values.minOrNull(),
            max = values.maxOrNull(),
            reliableCoveragePct = covered * 100.0 / sessionDurationMs,
            observationCount = observations.size,
            algorithmId = ALGORITHM_ID.takeIf { observations.isNotEmpty() },
        )
    }

    private fun weightedMedian(weighted: List<Pair<Double, Long>>): Double? {
        val total = weighted.sumOf { it.second }
        if (total <= 0L) return null
        var accumulated = 0L
        weighted.sortedBy { it.first }.forEach { (value, weight) ->
            accumulated += weight
            if (accumulated * 2L >= total) return value
        }
        return weighted.maxBy { it.first }.first
    }
}

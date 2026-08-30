package app.galaxyvitals.wear.ui

import app.galaxyvitals.domain.EcgSampleFlags
import app.galaxyvitals.wear.sensors.EcgBatch
import app.galaxyvitals.wear.sensors.HeartRateSample

/**
 * Accepts a Samsung processed-HR value only after three distinct successful
 * readings agree closely enough to serve as a pre-measurement display seed.
 */
internal class HeartRatePreflightGate(
    private val requiredSamples: Int = REQUIRED_SAMPLES,
    private val maxSpreadBpm: Int = MAX_SPREAD_BPM,
    private val minimumSpanMs: Long = MINIMUM_SPAN_MS,
) {
    private val valid = ArrayDeque<HeartRateSample>(requiredSamples)

    init {
        require(requiredSamples >= 2)
        require(maxSpreadBpm >= 0)
        require(minimumSpanMs >= 0L)
    }

    fun reset() {
        valid.clear()
    }

    fun offer(sample: HeartRateSample): HeartRateSample? {
        if (!sample.isHeartRateValid) {
            reset()
            return null
        }
        val previousTimestamp = valid.lastOrNull()?.sensorTimestampMs
        if (previousTimestamp == sample.sensorTimestampMs) return null
        if (previousTimestamp != null && sample.sensorTimestampMs < previousTimestamp) reset()
        valid.addLast(sample)
        while (valid.size > requiredSamples) valid.removeFirst()
        if (valid.size < requiredSamples) return null

        val spanMs = valid.last().sensorTimestampMs - valid.first().sensorTimestampMs
        val minBpm = valid.minOf(HeartRateSample::bpm)
        val maxBpm = valid.maxOf(HeartRateSample::bpm)
        if (spanMs < minimumSpanMs || maxBpm - minBpm > maxSpreadBpm) return null

        return valid.sortedBy(HeartRateSample::bpm)[valid.size / 2]
    }

    private companion object {
        const val REQUIRED_SAMPLES = 3
        const val MAX_SPREAD_BPM = 5
        const val MINIMUM_SPAN_MS = 1_500L
    }
}

/** Sustained contact/quality gate used only by the bounded ECG preview listener. */
internal class EcgPreflightGate(
    private val requiredValidSamples: Int = REQUIRED_VALID_SAMPLES,
) {
    var validSampleCount: Int = 0
        private set
    private var lastSequence = -1
    private var lastSensorTimestampMs = -1L

    init {
        require(requiredValidSamples > 0)
    }

    fun reset() {
        validSampleCount = 0
        lastSequence = -1
        lastSensorTimestampMs = -1L
    }

    fun offer(batch: EcgBatch): Boolean {
        if (!batch.preflightSignalUsable()) {
            reset()
            return false
        }
        val expectedSequence = (lastSequence + 1) and 0xff
        val sequenceContinuous = lastSequence < 0 || batch.sequence == expectedSequence
        val timestampOrdered = lastSensorTimestampMs < 0L ||
            batch.sensorTimestampsMs.first() >= lastSensorTimestampMs
        if (!sequenceContinuous || !timestampOrdered) reset()
        validSampleCount = (validSampleCount + batch.samplesMv.size).coerceAtMost(requiredValidSamples)
        lastSequence = batch.sequence
        lastSensorTimestampMs = batch.sensorTimestampsMs.last()
        return validSampleCount >= requiredValidSamples
    }

    private companion object {
        const val REQUIRED_VALID_SAMPLES = 750 // 1.5 s at Samsung ECG's 500 Hz.
    }
}

private fun EcgBatch.preflightSignalUsable(): Boolean {
    if (!contactValid || samplesMv.isEmpty() || samplesMv.any { !it.isFinite() }) return false
    for (index in samplesMv.indices) {
        val value = samplesMv[index]
        if (sampleFlags[index] != EcgSampleFlags.NONE) return false
        if (minThresholdMv?.let { value < it } == true) return false
        if (maxThresholdMv?.let { value > it } == true) return false
    }
    return true
}

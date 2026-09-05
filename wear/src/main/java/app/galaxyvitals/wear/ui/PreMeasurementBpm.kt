package app.galaxyvitals.wear.ui

import app.galaxyvitals.wear.sensors.HeartRateSample

/**
 * The Samsung heart rate accepted before the ECG capture, and what it is for.
 *
 * The watch shows a number from the moment the gate accepts one, but that
 * number is not ECG-derived and must never be presented as though it were: it
 * is held, explicitly labelled, until the capture's own estimate is reliable,
 * and it is recorded into the file as a PREFLIGHT observation with its own
 * source so the phone can tell the two apart afterwards.
 *
 * This is also the fallback the display falls back *to* - if live ECG BPM stops
 * being reliable mid-capture the screen returns here rather than going blank.
 */
internal class PreMeasurementBpm(private val elapsedRealtime: () -> Long) {

    var sample: HeartRateSample? = null
        private set

    var acceptedAtElapsedMs: Long = 0L
        private set

    fun reset() {
        sample = null
        acceptedAtElapsedMs = 0L
    }

    fun accept(accepted: HeartRateSample) {
        sample = accepted
        acceptedAtElapsedMs = elapsedRealtime()
    }

    /** Milliseconds since the reading was accepted, for the recorded observation. */
    fun ageMs(): Long = (elapsedRealtime() - acceptedAtElapsedMs).coerceAtLeast(0L)

    /** Per-beat intervals Samsung supplied, used to corroborate the ECG estimate. */
    fun samsungIbiMs(): List<Int> = sample?.validIbiMs.orEmpty()

    /**
     * What the screen shows while ECG-derived BPM is not yet reliable.
     *
     * COLLECTING until a reading is accepted; after that the held preflight
     * value, tagged with its own epoch and source so it cannot be mistaken for
     * an ECG measurement.
     */
    fun heldState(): LiveBpmState {
        val accepted = sample ?: return LiveBpmState(LiveBpmAvailability.COLLECTING)
        return LiveBpmState(
            availability = LiveBpmAvailability.RELIABLE,
            estimate = BpmEstimate(
                bpm = accepted.bpm.toDouble(),
                source = BpmSource.SAMSUNG_PROCESSED_HR,
                epoch = BpmEpoch.PREFLIGHT,
                rrCount = accepted.validIbiMs.size,
                updatedAtElapsedMs = acceptedAtElapsedMs,
            ),
            estimateAgeMs = ageMs(),
        )
    }
}

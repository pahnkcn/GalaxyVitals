package app.galaxyvitals.wear.ui

import app.galaxyvitals.wear.sensors.EcgBatch

/**
 * How much of a capture reaches logcat.
 *
 * A 30 s capture delivers ~3000 batches, so acquisition logging is rate limited
 * to one line a second - except when the lead-off bits change, which is the one
 * thing worth seeing the instant it happens. Phase lines are emitted only when
 * the phase or its reason code actually changes, so a retry loop does not bury
 * the transition that mattered.
 *
 * Keeping that bookkeeping here is what lets the coordinator's own state be
 * about the measurement rather than about what has already been printed.
 */
internal class MeasureTransitionLog(
    private val transitionLogger: (String) -> Unit,
    private val acquisitionLogger: (String) -> Unit,
) {
    private var lastLoggedPhase: MeasurePhase? = null
    private var lastLoggedCode: String? = null
    private var lastAcquisitionLogAt = 0L
    private var lastLoggedLeadOff: Int? = null

    /** Forget what has been printed, so a new attempt logs its first phase. */
    fun resetAttempt() {
        lastLoggedPhase = null
        lastLoggedCode = null
        lastAcquisitionLogAt = 0L
        lastLoggedLeadOff = null
    }

    /** Forget the last lead-off value, so the next batch logs unconditionally. */
    fun resetLeadOff() {
        lastLoggedLeadOff = null
    }

    fun phase(
        attemptId: Long,
        phase: MeasurePhase,
        code: String,
        samples: Int,
        elapsedMs: Long,
    ) {
        if (phase == lastLoggedPhase && code == lastLoggedCode) return
        lastLoggedPhase = phase
        lastLoggedCode = code
        transitionLogger(
            "attempt=$attemptId phase=${phase.name} code=$code samples=$samples " +
                "elapsedMs=$elapsedMs",
        )
    }

    fun batch(
        phase: MeasurePhase,
        batch: EcgBatch,
        generation: Long,
        samples: Int,
        now: Long,
    ) {
        val force = lastLoggedLeadOff != batch.leadOff
        if (!force && lastAcquisitionLogAt != 0L && now - lastAcquisitionLogAt < ACQUISITION_LOG_INTERVAL_MS) return
        lastAcquisitionLogAt = now
        lastLoggedLeadOff = batch.leadOff
        acquisitionLogger(
            "phase=${phase.name} leadOff=${batch.leadOff} sequence=${batch.sequence} " +
                "batchSize=${batch.samplesMv.size} generation=$generation " +
                "samples=$samples",
        )
    }

    private companion object {
        const val ACQUISITION_LOG_INTERVAL_MS = 1_000L
    }
}

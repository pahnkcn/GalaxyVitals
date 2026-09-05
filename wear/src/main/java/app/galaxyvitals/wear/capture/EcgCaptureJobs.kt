package app.galaxyvitals.wear.capture

import kotlinx.coroutines.Job

/**
 * The timers and workers one capture attempt owns.
 *
 * Every one of these outlives the call that started it and has to be cancelled
 * on any path out of an attempt - success, failure, cancellation, host stop,
 * shutdown. Holding them together is what makes "cancel everything this attempt
 * started" a single statement that cannot be half-written.
 */
internal class EcgCaptureJobs {
    var connectTimeout: Job? = null
    var heartRateWait: Job? = null
    var contactWait: Job? = null
    var countdown: Job? = null
    var streamMonitor: Job? = null
    var bpmWorker: Job? = null
    var bpmTicker: Job? = null

    fun cancelAll() {
        connectTimeout?.cancel()
        connectTimeout = null
        heartRateWait?.cancel()
        heartRateWait = null
        contactWait?.cancel()
        contactWait = null
        countdown?.cancel()
        countdown = null
        streamMonitor?.cancel()
        streamMonitor = null
        bpmWorker?.cancel()
        bpmWorker = null
        bpmTicker?.cancel()
        bpmTicker = null
    }
}

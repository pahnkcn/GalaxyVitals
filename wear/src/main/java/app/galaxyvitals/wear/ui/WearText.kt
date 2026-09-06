package app.galaxyvitals.wear.ui

import androidx.annotation.StringRes
import app.galaxyvitals.wear.R
import app.galaxyvitals.data.protocol.EcgSyncSemantics

/**
 * Turns the measurement state machine's own words into translated ones.
 *
 * [EcgMeasurementCoordinator] emits status and error text as fixed tokens —
 * some of them, like the sync semantics, are part of the watch/phone contract
 * and are asserted on in tests. Rather than reshape a state machine so the UI
 * can be translated, the display layer looks each token up here. Anything not
 * listed is Samsung's own text arriving from the sensor SDK, which is shown
 * unchanged because the app has no translation for it.
 */
object WearText {

    @StringRes
    fun statusRes(status: String): Int? = when (status) {
        "Connecting sensor…", "Connecting…" -> R.string.wear_status_connecting
        "Samsung Health setup needed" -> R.string.wear_status_samsung_setup
        "Preparing heart rate…" -> R.string.wear_status_preparing_hr
        "Touch the sensor to begin" -> R.string.wear_status_touch_sensor
        "Stabilizing ECG…" -> R.string.wear_status_stabilizing
        "Starting in" -> R.string.wear_status_countdown
        "Confirming sensor contact…" -> R.string.wear_status_confirming_contact
        "Recording" -> R.string.wear_status_recording
        "Saving…" -> R.string.wear_status_saving
        "Save failed" -> R.string.wear_status_save_failed
        "Recording failed" -> R.string.wear_status_recording_failed
        "Recording cancelled" -> R.string.wear_status_recording_cancelled
        "Sensor permissions needed" -> R.string.wear_status_permissions
        "ECG sensor not available" -> R.string.wear_status_sensor_unavailable
        "Heart rate not ready" -> R.string.wear_status_hr_not_ready
        "Sensor contact not detected" -> R.string.wear_status_contact_not_detected
        EcgSyncSemantics.QUEUED -> R.string.wear_status_queued
        EcgSyncSemantics.ACKNOWLEDGED -> R.string.wear_status_acknowledged
        EcgSyncSemantics.SAVED_ON_WATCH -> R.string.wear_status_saved_on_watch
        else -> null
    }

    @StringRes
    fun messageRes(message: String): Int? = when (message) {
        "Phone not linked. Keep GalaxyVitals open nearby, then Sync." ->
            R.string.wear_msg_phone_not_linked_sync
        "No connected phone. Keep the GalaxyVitals phone app nearby and try Sync." ->
            R.string.wear_msg_no_connected_phone
        "Could not save this recording. Please try again." -> R.string.wear_msg_save_failed
        "Start again when ready." -> R.string.wear_msg_start_again
        "ECG contact was lost.", "ECG contact was lost" -> R.string.wear_msg_contact_lost
        "Watch not worn properly." -> R.string.wear_msg_not_worn
        "Keep the watch snug and stay still, then try again." -> R.string.wear_msg_keep_snug
        "Wear the watch and keep a finger on the top sensor, then try again." ->
            R.string.wear_msg_wear_and_hold
        "A stable heart-rate value is required before ECG recording." ->
            R.string.wear_msg_hr_required
        "Samsung returned an invalid ECG batch." -> R.string.wear_msg_invalid_batch
        "Invalid ECG signal." -> R.string.wear_msg_invalid_signal
        "The ECG recording did not complete. Please try again.",
        "The ECG recording is incomplete." -> R.string.wear_msg_incomplete
        "Unexpected capture error." -> R.string.wear_msg_unexpected
        "ECG sensor stopped sending data." -> R.string.wear_msg_stream_stalled
        "Samsung ECG connection timed out." -> R.string.wear_msg_connect_timeout
        "Samsung ECG service could not start." -> R.string.wear_msg_service_start
        "Samsung heart-rate tracker could not start." -> R.string.wear_msg_hr_start
        "Samsung ECG is not available for this package." -> R.string.wear_msg_not_available_pkg
        "Samsung ECG tracker ready" -> R.string.wear_sensor_ready
        else -> null
    }

    /** The live rate's source, named for the reader rather than for the sensor. */
    @StringRes
    fun heartRateSourceRes(epoch: BpmEpoch?, source: BpmSource?): Int = when {
        epoch == BpmEpoch.PREFLIGHT -> R.string.wear_hr_preflight
        source == BpmSource.SAMSUNG_PROCESSED_HR -> R.string.wear_hr_samsung
        else -> R.string.wear_hr_live
    }
}

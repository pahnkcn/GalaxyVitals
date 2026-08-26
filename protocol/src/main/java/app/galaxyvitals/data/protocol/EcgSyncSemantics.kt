package app.galaxyvitals.data.protocol

/** Watch/phone copy for Data Layer delivery vs per-session acknowledgement. */
object EcgSyncSemantics {
    const val QUEUED = "QUEUED"
    const val ACKNOWLEDGED = "ACKNOWLEDGED"
    const val SAVED_ON_WATCH = "Saved on watch"
    const val LIVE_ECG_DERIVED_BPM = "Live ECG-derived BPM"

    fun afterPutDataItem(pushed: Boolean): String =
        if (pushed) QUEUED else SAVED_ON_WATCH

    fun fromAckMarker(acknowledged: Boolean): String =
        if (acknowledged) ACKNOWLEDGED else QUEUED
}

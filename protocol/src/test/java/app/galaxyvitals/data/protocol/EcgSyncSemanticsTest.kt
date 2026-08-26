package app.galaxyvitals.data.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EcgSyncSemanticsTest {
    @Test
    fun putDataItemSuccessIsQueuedUntilAckMarker() {
        assertThat(EcgSyncSemantics.afterPutDataItem(pushed = true))
            .isEqualTo(EcgSyncSemantics.QUEUED)
        assertThat(EcgSyncSemantics.afterPutDataItem(pushed = true))
            .isNotEqualTo(EcgSyncSemantics.ACKNOWLEDGED)
        assertThat(EcgSyncSemantics.afterPutDataItem(pushed = true))
            .isNotEqualTo("Sent to phone")
        assertThat(EcgSyncSemantics.afterPutDataItem(pushed = false))
            .isEqualTo(EcgSyncSemantics.SAVED_ON_WATCH)
        assertThat(EcgSyncSemantics.fromAckMarker(acknowledged = false))
            .isEqualTo(EcgSyncSemantics.QUEUED)
        assertThat(EcgSyncSemantics.fromAckMarker(acknowledged = true))
            .isEqualTo(EcgSyncSemantics.ACKNOWLEDGED)
        assertThat(EcgSyncSemantics.QUEUED).isNotEqualTo(EcgSyncSemantics.ACKNOWLEDGED)
        assertThat(EcgSyncSemantics.LIVE_ECG_DERIVED_BPM).isEqualTo("Live ECG-derived BPM")
    }
}

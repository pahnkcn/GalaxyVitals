package app.galaxyvitals.wear.sensors

import com.google.common.truth.Truth.assertThat
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey
import org.junit.Test

class SamsungHeartRateSessionTest {
    @Test
    fun deliveryKeepsSamsungBatchOrder() {
        val tracker = RecordingHealthTracker()
        val delivered = arrayListOf<HeartRateBatch>()
        val subscription = SamsungHeartRateSession(tracker).start(
            onError = {},
            onBatch = { delivered += it },
        )

        tracker.listener!!.onDataReceived(listOf(point(70, 1, 1_000L), point(71, 1, 2_000L)))

        assertThat(delivered).hasSize(1)
        assertThat(delivered.single().samples.map { it.bpm }).containsExactly(70, 71).inOrder()
        subscription.close()
    }

    @Test
    fun subscriptionCloseIsIdempotentAndRejectsLaterCallbacks() {
        val tracker = RecordingHealthTracker()
        var current = true
        val delivered = arrayListOf<HeartRateBatch>()
        val subscription = SamsungHeartRateSession(
            tracker = tracker,
            isCurrent = { current },
        ).start(onError = {}, onBatch = { delivered += it })

        subscription.close()
        subscription.close()
        current = false
        tracker.listener!!.onDataReceived(listOf(point(72, 1, 1_000L)))

        assertThat(tracker.unsetCount).isEqualTo(1)
        assertThat(delivered).isEmpty()
    }

    @Test
    fun permissionErrorUsesTypedRecovery() {
        val tracker = RecordingHealthTracker()
        val errors = arrayListOf<EcgSensorError>()
        SamsungHeartRateSession(tracker).start(onError = { errors += it }, onBatch = {})

        tracker.listener!!.onError(HealthTracker.TrackerError.PERMISSION_ERROR)

        assertThat(errors).hasSize(1)
        assertThat(errors.single().issue?.code).isEqualTo(SensorIssueCode.PERMISSION_ERROR)
        assertThat(errors.single().issue?.recovery).isEqualTo(SensorRecovery.REQUEST_PERMISSION)
    }

    private fun point(bpm: Int, status: Int, timestamp: Long): DataPoint = DataPoint(
        values = mapOf(
            ValueKey.HeartRateSet.HEART_RATE to bpm,
            ValueKey.HeartRateSet.HEART_RATE_STATUS to status,
            ValueKey.HeartRateSet.IBI_LIST to emptyList<Int>(),
            ValueKey.HeartRateSet.IBI_STATUS_LIST to emptyList<Int>(),
        ),
        timestamp = timestamp,
    )

    private class RecordingHealthTracker : HealthTracker() {
        var listener: TrackerEventListener? = null
        var unsetCount = 0

        override fun setEventListener(listener: TrackerEventListener) {
            this.listener = listener
        }

        override fun unsetEventListener() {
            unsetCount += 1
        }
    }
}

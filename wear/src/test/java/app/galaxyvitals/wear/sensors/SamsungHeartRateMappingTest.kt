package app.galaxyvitals.wear.sensors

import com.google.common.truth.Truth.assertThat
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey
import org.junit.Assert.assertThrows
import org.junit.Test

class SamsungHeartRateMappingTest {
    @Test
    fun successfulHeartRateAndNormalNonzeroIbiAreValid() {
        val batch = SamsungHeartRateMapping.mapBatch(
            listOf(point(bpm = 72, status = 1, ibi = listOf(833, 0, 840), ibiStatus = listOf(0, 0, -1))),
        )

        val sample = batch.samples.single()
        assertThat(sample.sensorTimestampMs).isEqualTo(1_000L)
        assertThat(sample.isHeartRateValid).isTrue()
        assertThat(sample.validIbiMs).containsExactly(833)
        assertThat(sample.ibiMs).containsExactly(833, 0, 840).inOrder()
        assertThat(sample.ibiStatus).containsExactly(0, 0, -1).inOrder()
    }

    @Test
    fun weakSignalStatusIsNotValidHeartRate() {
        val sample = SamsungHeartRateMapping.mapBatch(
            listOf(point(bpm = 72, status = -10)),
        ).samples.single()

        assertThat(sample.isHeartRateValid).isFalse()
    }

    @Test
    fun nullIbiListsBecomeEmptyForBatchedPoints() {
        val sample = SamsungHeartRateMapping.mapBatch(
            listOf(point(bpm = 71, status = 1, ibi = null, ibiStatus = null)),
        ).samples.single()

        assertThat(sample.ibiMs).isEmpty()
        assertThat(sample.ibiStatus).isEmpty()
    }

    @Test
    fun mismatchedIbiListsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SamsungHeartRateMapping.mapBatch(
                listOf(point(bpm = 71, status = 1, ibi = listOf(833), ibiStatus = emptyList())),
            )
        }
    }

    @Test
    fun negativeIbiValueIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SamsungHeartRateMapping.mapBatch(
                listOf(point(bpm = 71, status = 1, ibi = listOf(-1), ibiStatus = listOf(0))),
            )
        }
    }

    private fun point(
        bpm: Int,
        status: Int,
        ibi: List<Int>? = emptyList(),
        ibiStatus: List<Int>? = emptyList(),
    ): DataPoint = DataPoint(
        values = mapOf(
            ValueKey.HeartRateSet.HEART_RATE to bpm,
            ValueKey.HeartRateSet.HEART_RATE_STATUS to status,
            ValueKey.HeartRateSet.IBI_LIST to ibi,
            ValueKey.HeartRateSet.IBI_STATUS_LIST to ibiStatus,
        ),
        timestamp = 1_000L,
    )
}

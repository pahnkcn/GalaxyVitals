package app.galaxyvitals.wear.sensors

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SamsungPpgGreenDecoderTest {
    @Test
    fun batchSize5_readsOffsetZeroOnly() {
        val valuesByOffset = mapOf(0 to 12_345)
        val timestampsByOffset = mapOf(0 to 1_000L)
        val probed = mutableListOf<Int>()

        val decoded = SamsungPpgGreenDecoder.decode(
            batchSize = 5,
            timestampAt = { offset ->
                timestampsByOffset.getValue(offset)
            },
            valueAt = { offset ->
                probed += offset
                check(offset == 0) { "valueAt called for undocumented offset $offset" }
                valuesByOffset[offset]
            },
        )

        requireNotNull(decoded)
        assertThat(decoded.values.contentEquals(intArrayOf(12_345))).isTrue()
        assertThat(decoded.ecgSampleOffsets.contentEquals(intArrayOf(0))).isTrue()
        assertThat(decoded.sensorTimestampsMs.contentEquals(longArrayOf(1_000L))).isTrue()
        assertThat(decoded.nominalSampleRateHz).isEqualTo(100)
        assertThat(probed).containsExactly(0)
    }

    @Test
    fun batchSize10_readsOffsetsZeroAndFive() {
        val valuesByOffset = mapOf(0 to 100, 5 to 200)
        val timestampsByOffset = mapOf(0 to 2_000L, 5 to 2_010L)
        val probed = mutableListOf<Int>()

        val decoded = SamsungPpgGreenDecoder.decode(
            batchSize = 10,
            timestampAt = { offset ->
                timestampsByOffset.getValue(offset)
            },
            valueAt = { offset ->
                probed += offset
                check(offset == 0 || offset == 5) { "valueAt called for undocumented offset $offset" }
                valuesByOffset[offset]
            },
        )

        requireNotNull(decoded)
        assertThat(decoded.values.contentEquals(intArrayOf(100, 200))).isTrue()
        assertThat(decoded.ecgSampleOffsets.contentEquals(intArrayOf(0, 5))).isTrue()
        assertThat(decoded.sensorTimestampsMs.contentEquals(longArrayOf(2_000L, 2_010L))).isTrue()
        assertThat(probed).containsExactly(0, 5).inOrder()
    }

    @Test
    fun missingPpgAtRequiredOffset_returnsNull() {
        val decoded = SamsungPpgGreenDecoder.decode(
            batchSize = 5,
            timestampAt = { 1_000L },
            valueAt = { null },
        )
        assertThat(decoded).isNull()
    }

    @Test
    fun unexpectedBatchSize_returnsNullWithoutProbing() {
        var probed = false
        val decoded = SamsungPpgGreenDecoder.decode(
            batchSize = 7,
            timestampAt = { error("timestampAt should not be called") },
            valueAt = {
                probed = true
                1
            },
        )
        assertThat(decoded).isNull()
        assertThat(probed).isFalse()
    }

    @Test
    fun batchSize10_missingSecondOffset_returnsNull() {
        val decoded = SamsungPpgGreenDecoder.decode(
            batchSize = 10,
            timestampAt = { offset -> offset * 2L },
            valueAt = { offset ->
                check(offset == 0 || offset == 5) { "valueAt called for undocumented offset $offset" }
                if (offset == 0) 10 else null
            },
        )
        assertThat(decoded).isNull()
    }

    @Test
    fun ppgGreenBatch_contentEqualityAndHashCode() {
        val left = PpgGreenBatch(
            values = intArrayOf(100, 200),
            ecgSampleOffsets = intArrayOf(0, 5),
            sensorTimestampsMs = longArrayOf(1_000L, 1_010L),
            nominalSampleRateHz = 100,
        )
        val right = PpgGreenBatch(
            values = intArrayOf(100, 200),
            ecgSampleOffsets = intArrayOf(0, 5),
            sensorTimestampsMs = longArrayOf(1_000L, 1_010L),
            nominalSampleRateHz = 100,
        )
        val different = PpgGreenBatch(
            values = intArrayOf(100, 201),
            ecgSampleOffsets = intArrayOf(0, 5),
            sensorTimestampsMs = longArrayOf(1_000L, 1_010L),
            nominalSampleRateHz = 100,
        )

        assertThat(left).isEqualTo(right)
        assertThat(left.hashCode()).isEqualTo(right.hashCode())
        assertThat(left).isNotEqualTo(different)
    }
}

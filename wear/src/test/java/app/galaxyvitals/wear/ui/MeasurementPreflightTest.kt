package app.galaxyvitals.wear.ui

import app.galaxyvitals.domain.EcgSampleFlags
import app.galaxyvitals.wear.sensors.EcgBatch
import app.galaxyvitals.wear.sensors.HeartRateSample
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MeasurementPreflightTest {
    @Test
    fun heartRateRequiresThreeDistinctStableSamplesAcrossMinimumSpan() {
        val gate = HeartRatePreflightGate()

        assertThat(gate.offer(heartRate(timestampMs = 1_000L, bpm = 70))).isNull()
        assertThat(gate.offer(heartRate(timestampMs = 1_000L, bpm = 71))).isNull()
        assertThat(gate.offer(heartRate(timestampMs = 2_000L, bpm = 72))).isNull()
        val accepted = gate.offer(heartRate(timestampMs = 3_000L, bpm = 71))

        assertThat(accepted).isNotNull()
        assertThat(accepted!!.bpm).isEqualTo(71)
        assertThat(accepted.sensorTimestampMs).isEqualTo(3_000L)
    }

    @Test
    fun heartRateInvalidOrUnstableReadingsCannotPass() {
        val gate = HeartRatePreflightGate()

        assertThat(gate.offer(heartRate(1_000L, 70))).isNull()
        assertThat(gate.offer(heartRate(2_000L, 80))).isNull()
        assertThat(gate.offer(heartRate(3_000L, 70))).isNull()
        assertThat(gate.offer(heartRate(4_000L, 0, status = -10))).isNull()
        assertThat(gate.offer(heartRate(5_000L, 71))).isNull()
        assertThat(gate.offer(heartRate(6_000L, 72))).isNull()
        assertThat(gate.offer(heartRate(7_000L, 73))!!.bpm).isEqualTo(72)
    }

    @Test
    fun ecgRequires750ConsecutiveUsableSamples() {
        val gate = EcgPreflightGate()

        assertThat(gate.offer(ecg(count = 749))).isFalse()
        assertThat(gate.validSampleCount).isEqualTo(749)
        assertThat(gate.offer(ecg(count = 1, sequence = 1, timestampStartMs = 2_498L))).isTrue()
        assertThat(gate.validSampleCount).isEqualTo(750)
    }

    @Test
    fun ecgContactLossOrClippingRestartsTheSettlingWindow() {
        val gate = EcgPreflightGate()

        assertThat(gate.offer(ecg(count = 500))).isFalse()
        assertThat(
            gate.offer(ecg(count = 10, leadOff = 1, sequence = 1, timestampStartMs = 2_000L)),
        ).isFalse()
        assertThat(gate.validSampleCount).isEqualTo(0)
        assertThat(gate.offer(ecg(count = 500, sequence = 2, timestampStartMs = 3_000L))).isFalse()
        assertThat(
            gate.offer(
                ecg(
                    count = 10,
                    sequence = 3,
                    timestampStartMs = 4_000L,
                    flags = IntArray(10).also { it[0] = EcgSampleFlags.CLIPPED },
                ),
            ),
        ).isFalse()
        assertThat(gate.validSampleCount).isEqualTo(0)
        assertThat(gate.offer(ecg(count = 750, sequence = 4, timestampStartMs = 5_000L))).isTrue()
    }

    @Test
    fun ecgSequenceGapRestartsTheSettlingWindowFromCurrentBatch() {
        val gate = EcgPreflightGate()

        assertThat(gate.offer(ecg(count = 500, sequence = 7))).isFalse()
        assertThat(gate.offer(ecg(count = 300, sequence = 9, timestampStartMs = 2_000L))).isFalse()
        assertThat(gate.validSampleCount).isEqualTo(300)
        assertThat(gate.offer(ecg(count = 450, sequence = 10, timestampStartMs = 2_600L))).isTrue()
    }

    private fun heartRate(timestampMs: Long, bpm: Int, status: Int = 1): HeartRateSample =
        HeartRateSample(
            sensorTimestampMs = timestampMs,
            bpm = bpm,
            status = status,
            ibiMs = emptyList(),
            ibiStatus = emptyList(),
        )

    private fun ecg(
        count: Int,
        sequence: Int = 0,
        leadOff: Int = 0,
        timestampStartMs: Long = 1_000L,
        flags: IntArray = IntArray(count),
    ): EcgBatch = EcgBatch(
        samplesMv = FloatArray(count) { 0.1f },
        sensorTimestampsMs = LongArray(count) { timestampStartMs + it * 2L },
        sequence = sequence,
        leadOff = leadOff,
        minThresholdMv = -5f,
        maxThresholdMv = 5f,
        sampleFlags = flags,
    )
}

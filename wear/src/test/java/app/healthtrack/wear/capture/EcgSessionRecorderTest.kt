package app.healthtrack.wear.capture

import app.healthtrack.data.protocol.EcgCsvParser
import app.healthtrack.data.protocol.EcgWearContract
import app.healthtrack.domain.Wrist
import app.healthtrack.domain.EcgSampleFlags
import app.healthtrack.wear.sensors.EcgBatch
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.Assert.assertThrows

class EcgSessionRecorderTest {
    @Test
    fun finishProducesParsableGzip() {
        val recorder = EcgSessionRecorder()
        val start = 1_700_000_010_000L
        recorder.begin("1700000010000", Wrist.LEFT, 1, start)
        recorder.addEcg(FloatArray(500) { i -> if (i % 50 == 0) 1.2f else 0.05f }, applySign = true)
        recorder.addHr(start, 70)
        recorder.addHr(start + 400, 72)
        val recorded = recorder.finish("""{"model":"unit"}""")
        val parsed = EcgCsvParser.parseBytes(recorded.gzip, gzip = true, sessionIdHint = recorded.sessionId)
        assertThat(parsed.sessionId).isEqualTo("1700000010000")
        assertThat(parsed.srHz).isEqualTo(EcgWearContract.DEFAULT_SR_HZ)
        assertThat(parsed.samples.size).isEqualTo(500)
        assertThat(parsed.hrMin).isNull()
        assertThat(parsed.schemaVersion).isEqualTo(2)
        assertThat(parsed.signFactor).isEqualTo(1)
    }

    @Test
    fun rightWristPreservesRawSign() {
        val recorder = EcgSessionRecorder()
        recorder.begin("1", Wrist.RIGHT, -1, 1000L)
        recorder.addEcg(floatArrayOf(0.5f, 0.25f))
        recorder.addHr(1000L, 60)
        val parsed = EcgCsvParser.parseBytes(
            recorder.finish("w").gzip,
            gzip = true,
            sessionIdHint = "1",
        )
        assertThat(parsed.signFactor).isEqualTo(-1)
        assertThat(parsed.samples[0].valueMv).isEqualTo(0.5f)
        assertThat(parsed.samples[1].valueMv).isEqualTo(0.25f)
    }

    @Test
    fun sampleBufferOverflowFailsWithoutTruncating() {
        val recorder = EcgSessionRecorder()
        recorder.begin("cap", Wrist.LEFT, 1, 1000L)

        assertThrows(EcgCaptureException::class.java) {
            recorder.addEcg(FloatArray(EcgSessionRecorder.MAX_SAMPLES + 500) { 0.1f })
        }
        assertThat(recorder.sampleCount).isEqualTo(0)
    }

    @Test
    fun nonFiniteBatchIsRejectedWithoutCorruptingSession() {
        val recorder = EcgSessionRecorder()
        recorder.begin("finite", Wrist.LEFT, 1, 1000L)
        recorder.addEcg(floatArrayOf(0.1f))
        assertThrows(EcgCaptureException::class.java) {
            recorder.addEcg(floatArrayOf(0.2f, Float.NaN, Float.POSITIVE_INFINITY))
        }
        recorder.addEcg(floatArrayOf(0.3f))

        val parsed = EcgCsvParser.parseBytes(
            recorder.finish("w").gzip,
            gzip = true,
            sessionIdHint = "finite",
        )

        assertThat(parsed.samples.map { it.valueMv }).containsExactly(0.1f, 0.3f).inOrder()
    }

    @Test
    fun immutableSnapshotSurvivesCancelAndRecorderReuse() {
        val recorder = EcgSessionRecorder()
        recorder.begin("completed", Wrist.LEFT, 1, 1000L)
        recorder.addEcg(floatArrayOf(0.1f, 0.2f))
        val snapshot = recorder.takeSnapshot()

        recorder.cancel()
        recorder.begin("new", Wrist.LEFT, 1, 2000L)
        recorder.addEcg(floatArrayOf(0.9f))
        val completed = recorder.finish(snapshot, "w")

        val parsed = EcgCsvParser.parseBytes(
            completed.gzip,
            gzip = true,
            sessionIdHint = completed.sessionId,
        )
        assertThat(completed.sessionId).isEqualTo("completed")
        assertThat(completed.nSamples).isEqualTo(2)
        assertThat(parsed.samples.map { it.valueMv }).containsExactly(0.1f, 0.2f).inOrder()
        assertThat(recorder.isRecording).isTrue()
        assertThat(recorder.sessionId).isEqualTo("new")
        assertThat(recorder.sampleCount).isEqualTo(1)
    }

    @Test
    fun exactThirtySecondsIsIndependentOfFiveAndTenPointBatching() {
        val recorder = EcgSessionRecorder()
        recorder.begin("exact", Wrist.LEFT, 1, 1_000L)
        var index = 0
        var sequence = 250
        while (index < EcgSessionRecorder.EXPECTED_SAMPLES) {
            val requested = if ((index / 5) % 3 == 0) 5 else 10
            val count = minOf(requested, EcgSessionRecorder.EXPECTED_SAMPLES - index)
            recorder.addEcg(batch(index, count, sequence))
            index += count
            sequence = (sequence + 1) and 0xff
        }
        val snapshot = recorder.takeSnapshot()
        snapshot.requireCompleteHardwareCapture()
        val parsed = EcgCsvParser.parseBytes(
            recorder.finish(snapshot, "w").gzip, true, "exact",
        )
        assertThat(parsed.samples).hasSize(15_000)
        assertThat(parsed.samples.last().relMs).isEqualTo(29_998L)
    }

    @Test
    fun leadOffSaturationAndSequenceGapFailCapture() {
        listOf(-1, 1, 2, 5, 255).forEach { leadOff ->
            val lead = EcgSessionRecorder().apply { begin("lead", Wrist.LEFT, 1, 1L) }
            assertThrows(EcgCaptureException::class.java) {
                lead.addEcg(batch(0, 5, 0, leadOff = leadOff))
            }
        }

        val clipped = EcgSessionRecorder().apply { begin("clip", Wrist.LEFT, 1, 1L) }
        assertThrows(EcgCaptureException::class.java) {
            clipped.addEcg(batch(0, 5, 0, flags = IntArray(5) { EcgSampleFlags.CLIPPED }))
        }

        val sequence = EcgSessionRecorder().apply { begin("seq", Wrist.LEFT, 1, 1L) }
        sequence.addEcg(batch(0, 5, 255))
        sequence.addEcg(batch(5, 5, 0))
        assertThrows(EcgCaptureException::class.java) {
            sequence.addEcg(batch(10, 5, 0))
        }
    }

    private fun batch(
        firstIndex: Int,
        count: Int,
        sequence: Int,
        leadOff: Int = 0,
        flags: IntArray = IntArray(count),
    ): EcgBatch = EcgBatch(
        samplesMv = FloatArray(count) { 0.1f },
        sensorTimestampsMs = LongArray(count) { offset ->
            1_000L + (firstIndex + offset) * EcgSessionRecorder.EXPECTED_PERIOD_MS
        },
        sequence = sequence,
        leadOff = leadOff,
        minThresholdMv = -5f,
        maxThresholdMv = 5f,
        sampleFlags = flags,
    )
}

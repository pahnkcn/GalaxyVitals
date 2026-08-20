package app.healthtrack.wear.capture

import app.healthtrack.data.protocol.EcgCsvParser
import app.healthtrack.data.protocol.EcgWearContract
import app.healthtrack.domain.Wrist
import com.google.common.truth.Truth.assertThat
import org.junit.Test

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
        assertThat(parsed.hrMin).isEqualTo(70)
        assertThat(parsed.signFactor).isEqualTo(1)
    }

    @Test
    fun rightWristFlipsSign() {
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
        assertThat(parsed.samples[0].valueMv).isEqualTo(-0.5f)
        assertThat(parsed.samples[1].valueMv).isEqualTo(-0.25f)
    }

    @Test
    fun sampleBufferIsCapped() {
        val recorder = EcgSessionRecorder()
        recorder.begin("cap", Wrist.LEFT, 1, 1000L)

        recorder.addEcg(FloatArray(EcgSessionRecorder.MAX_SAMPLES + 500) { 0.1f })

        assertThat(recorder.sampleCount).isEqualTo(EcgSessionRecorder.MAX_SAMPLES)
        val recorded = recorder.finish("w")
        assertThat(recorded.nSamples).isEqualTo(EcgSessionRecorder.MAX_SAMPLES)
        val parsed = EcgCsvParser.parseBytes(recorded.gzip, gzip = true, sessionIdHint = "cap")
        assertThat(parsed.samples).hasSize(EcgSessionRecorder.MAX_SAMPLES)
    }

    @Test
    fun nonFiniteBatchIsRejectedWithoutCorruptingSession() {
        val recorder = EcgSessionRecorder()
        recorder.begin("finite", Wrist.LEFT, 1, 1000L)
        recorder.addEcg(floatArrayOf(0.1f))
        recorder.addEcg(floatArrayOf(0.2f, Float.NaN, Float.POSITIVE_INFINITY))
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
}

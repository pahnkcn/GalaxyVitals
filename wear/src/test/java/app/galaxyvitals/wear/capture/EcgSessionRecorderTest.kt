package app.galaxyvitals.wear.capture

import app.galaxyvitals.data.protocol.EcgCsvParser
import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.data.protocol.LiveBpmSummarizer
import app.galaxyvitals.domain.LiveBpmObservation
import app.galaxyvitals.domain.TimingTrust
import app.galaxyvitals.domain.Wrist
import app.galaxyvitals.domain.EcgSampleFlags
import app.galaxyvitals.wear.sensors.EcgBatch
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
        fillRemaining(recorder, firstIndex = 500, firstSequence = 1)
        recorder.addHr(start, 70)
        recorder.addHr(start + 400, 72)
        recorder.addBpmObservation(
            LiveBpmObservation(
                atSampleIndex = 500,
                observedCaptureElapsedMs = 1_000,
                status = "RELIABLE",
                displayedBpm = 70.0,
                rawBpm = 70.2,
                source = "APP_ECG_RR",
                bSqi = 0.93,
                rrCount = 7,
            ),
        )
        val recorded = recorder.finish("""{"model":"unit","sensorSdk":"1.4.1"}""")
        val parsed = EcgCsvParser.parseBytes(recorded.gzip, gzip = true, sessionIdHint = recorded.sessionId)
        assertThat(parsed.sessionId).isEqualTo("1700000010000")
        assertThat(parsed.srHz).isEqualTo(EcgWearContract.DEFAULT_SR_HZ)
        assertThat(parsed.samples.size).isEqualTo(EcgSessionRecorder.EXPECTED_SAMPLES)
        assertThat(parsed.hrMin).isNull()
        assertThat(parsed.schemaVersion).isEqualTo(3)
        assertThat(parsed.timingTrust).isEqualTo(TimingTrust.SEQUENCE_RECONSTRUCTED)
        assertThat(parsed.signFactor).isEqualTo(1)
        assertThat(parsed.bpmObservations).isNotEmpty()
        assertThat(parsed.liveBpmAlgorithmId)
            .isEqualTo(LiveBpmSummarizer.ALGORITHM_ID)
        assertThat(parsed.samples[0].sensorTimestampMsRaw).isNotNull()
    }

    @Test
    fun rightWristPreservesRawSign() {
        val recorder = EcgSessionRecorder()
        recorder.begin("1", Wrist.RIGHT, -1, 1000L)
        recorder.addEcg(floatArrayOf(0.5f, 0.25f))
        fillRemaining(recorder, firstIndex = 2, firstSequence = 1)
        recorder.addHr(1000L, 60)
        val parsed = EcgCsvParser.parseBytes(
            recorder.finish("w").gzip,
            gzip = true,
            sessionIdHint = "1",
        )
        assertThat(parsed.schemaVersion).isEqualTo(3)
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
        fillRemaining(recorder, firstIndex = 2, firstSequence = 2)

        val parsed = EcgCsvParser.parseBytes(
            recorder.finish("w").gzip,
            gzip = true,
            sessionIdHint = "finite",
        )

        assertThat(parsed.samples[0].valueMv).isEqualTo(0.1f)
        assertThat(parsed.samples[1].valueMv).isEqualTo(0.3f)
        assertThat(parsed.samples).hasSize(EcgSessionRecorder.EXPECTED_SAMPLES)
    }

    @Test
    fun immutableSnapshotSurvivesCancelAndRecorderReuse() {
        val recorder = EcgSessionRecorder()
        recorder.begin("completed", Wrist.LEFT, 1, 1000L)
        recorder.addEcg(floatArrayOf(0.1f, 0.2f))
        fillRemaining(recorder, firstIndex = 2, firstSequence = 1)
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
        assertThat(completed.nSamples).isEqualTo(EcgSessionRecorder.EXPECTED_SAMPLES)
        assertThat(parsed.schemaVersion).isEqualTo(3)
        assertThat(parsed.samples[0].valueMv).isEqualTo(0.1f)
        assertThat(parsed.samples[1].valueMv).isEqualTo(0.2f)
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
        snapshot.requireCompleteCapture()
        val parsed = EcgCsvParser.parseBytes(
            recorder.finish(snapshot, "w").gzip, true, "exact",
        )
        assertThat(parsed.samples).hasSize(15_000)
        assertThat(parsed.schemaVersion).isEqualTo(3)
        assertThat(parsed.samples.last().relMs).isEqualTo(29_998L)
        assertThat(parsed.samples.map { it.relMs }).isEqualTo((0 until 15_000).map { it * 2L })
    }

    @Test
    fun batchedIdenticalSensorTimestampsAreStoredOnAUniformFiveHundredHertzClock() {
        val recorder = EcgSessionRecorder()
        recorder.begin("batched", Wrist.LEFT, 1, 1_000L)
        var first = 0
        var sequence = 0
        while (first < EcgSessionRecorder.EXPECTED_SAMPLES) {
            val count = minOf(10, EcgSessionRecorder.EXPECTED_SAMPLES - first)
            val batchStart = 10_000L + sequence * 20L
            recorder.addEcg(
                batch(first, count, sequence and 0xff).copy(
                    sensorTimestampsMs = LongArray(count) { batchStart },
                ),
            )
            first += count
            sequence += 1
        }
        val parsed = EcgCsvParser.parseBytes(
            recorder.finish("w").gzip, true, "batched",
        )
        assertThat(parsed.schemaVersion).isEqualTo(3)
        assertThat(parsed.samples.map { it.relMs }).isEqualTo((0 until 15_000).map { it * 2L })
        assertThat(parsed.samples.map { it.sampleIndex }).isEqualTo((0 until 15_000).toList())
        assertThat(parsed.samples.take(10).map { it.sensorTimestampMsRaw }).isEqualTo(List(10) { 10_000L })
        assertThat(parsed.samples.take(10).map { it.batchSequence }).isEqualTo(List(10) { 0 })
        assertThat(parsed.samples.take(10).map { it.batchSampleOffset }).isEqualTo((0 until 10).toList())
        assertThat(parsed.samples.take(10).map { it.batchSize }).isEqualTo(List(10) { 10 })
        assertThat(parsed.repeatedTimestampCount).isGreaterThan(0)
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

    @Test
    fun localTimestampJitterPassesWhenAggregateRateIsWithinOnePercent() {
        val recorder = EcgSessionRecorder()
        recorder.begin("jitter", Wrist.LEFT, 1, 1L)
        var first = 0
        var sequence = 0
        while (first < EcgSessionRecorder.EXPECTED_SAMPLES) {
            val count = minOf(10, EcgSessionRecorder.EXPECTED_SAMPLES - first)
            recorder.addEcg(
                batch(first, count, sequence).copy(
                    sensorTimestampsMs = LongArray(count) { offset ->
                        val index = first + offset
                        1_000L + index * 2L - if (index % 2 == 1) 1L else 0L
                    },
                ),
            )
            first += count
            sequence = (sequence + 1) and 0xff
        }

        recorder.takeSnapshot().requireCompleteCapture()
    }

    @Test
    fun timestampReversalFailsButSlowRawClockDoesNotIfSequenceHolds() {
        val reversed = EcgSessionRecorder().apply { begin("reverse", Wrist.LEFT, 1, 1L) }
        reversed.addEcg(batch(0, 5, 0))
        assertThrows(EcgCaptureException::class.java) {
            reversed.addEcg(
                batch(5, 5, 1).copy(
                    sensorTimestampsMs = longArrayOf(1_007L, 1_012L, 1_014L, 1_016L, 1_018L),
                ),
            )
        }
        assertThat(reversed.sampleCount).isEqualTo(5)

        val slow = EcgSessionRecorder().apply { begin("slow", Wrist.LEFT, 1, 1L) }
        var first = 0
        var sequence = 0
        while (first < EcgSessionRecorder.EXPECTED_SAMPLES) {
            val count = minOf(10, EcgSessionRecorder.EXPECTED_SAMPLES - first)
            slow.addEcg(
                batch(first, count, sequence).copy(
                    sensorTimestampsMs = LongArray(count) { offset ->
                        1_000L + (first + offset) * 3L
                    },
                ),
            )
            first += count
            sequence = (sequence + 1) and 0xff
        }
        slow.takeSnapshot().requireCompleteCapture()
    }

    @Test
    fun saturationThresholdEqualityPassesButValuesBeyondItFail() {
        val atBoundary = EcgSessionRecorder().apply { begin("boundary", Wrist.LEFT, 1, 1L) }
        atBoundary.addEcg(
            batch(0, 2, 0).copy(samplesMv = floatArrayOf(-5f, 5f)),
        )
        assertThat(atBoundary.sampleCount).isEqualTo(2)

        val outside = EcgSessionRecorder().apply { begin("outside", Wrist.LEFT, 1, 1L) }
        assertThrows(EcgCaptureException::class.java) {
            outside.addEcg(
                batch(0, 2, 0).copy(samplesMv = floatArrayOf(-5.01f, 0f)),
            )
        }
    }

    @Test
    fun addEcgAtomicallyDoesNotCommitPartialBatch() {
        val recorder = EcgSessionRecorder()
        recorder.begin("atomic", Wrist.LEFT, 1, 1L)
        recorder.addEcg(batch(0, 5, 0))
        assertThrows(EcgCaptureException::class.java) {
            recorder.addEcgAtomically(
                batch(5, 5, 1).copy(samplesMv = floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f, 6f)),
            )
        }
        assertThat(recorder.sampleCount).isEqualTo(5)
        recorder.addEcg(batch(5, 5, 1))
        assertThat(recorder.sampleCount).isEqualTo(10)
    }

    @Test
    fun finishRejectsIncompleteSnapshot() {
        val recorder = EcgSessionRecorder()
        recorder.begin("short", Wrist.LEFT, 1, 1L)
        recorder.addEcg(batch(0, 5, 0))
        assertThrows(EcgCaptureException::class.java) {
            recorder.finish("w")
        }
        assertThat(recorder.sampleCount).isEqualTo(0)
    }

    @Test
    fun sequenceDropDoesNotMutateAndWrapIsAccepted() {
        val dropped = EcgSessionRecorder().apply { begin("drop", Wrist.LEFT, 1, 1L) }
        dropped.addEcg(batch(0, 5, 0))
        assertThrows(EcgCaptureException::class.java) {
            dropped.addEcg(batch(5, 5, 2))
        }
        assertThat(dropped.sampleCount).isEqualTo(5)
        dropped.addEcg(batch(5, 5, 1))
        fillRemaining(dropped, firstIndex = 10, firstSequence = 2)
        dropped.takeSnapshot().requireCompleteCapture()

        val wrapped = EcgSessionRecorder().apply { begin("wrap", Wrist.LEFT, 1, 1L) }
        wrapped.addEcg(batch(0, 5, 255))
        wrapped.addEcg(batch(5, 5, 0))
        assertThat(wrapped.sampleCount).isEqualTo(10)
        assertThrows(EcgCaptureException::class.java) {
            wrapped.addEcg(batch(10, 5, 0))
        }
        assertThat(wrapped.sampleCount).isEqualTo(10)
    }

    @Test
    fun lastPartialBatchKeepsOriginalBatchSize() {
        val recorder = EcgSessionRecorder()
        recorder.begin("prefix", Wrist.LEFT, 1, 1L)
        val nextSequence = fillRemaining(recorder, firstIndex = 0, firstSequence = 0, total = 14_995)
        recorder.addEcg(batch(14_995, 10, nextSequence and 0xff))
        val parsed = EcgCsvParser.parseBytes(recorder.finish("w").gzip, true, "prefix")
        assertThat(parsed.samples).hasSize(15_000)
        assertThat(parsed.samples[14_995].batchSize).isEqualTo(10)
        assertThat(parsed.samples[14_999].batchSampleOffset).isEqualTo(4)
        assertThat(parsed.samples[14_999].batchSequence).isEqualTo(nextSequence and 0xff)
    }

    @Test
    fun liveBpmObservationsAreCappedAndAttachedToSnapshot() {
        val recorder = EcgSessionRecorder()
        recorder.begin("bpm", Wrist.LEFT, 1, 1L)
        repeat(70) { index ->
            recorder.addBpmObservation(
                LiveBpmObservation(
                    atSampleIndex = index.toLong(),
                    observedCaptureElapsedMs = index * 100L,
                    status = if (index == 0) "COLLECTING" else "UNRELIABLE",
                    reasonCode = "INSUFFICIENT_RR",
                ),
            )
        }
        assertThat(recorder.liveBpmObservations()).hasSize(64)
        fillRemaining(recorder, firstIndex = 0, firstSequence = 0)
        val snapshot = recorder.takeSnapshot()
        recorder.cancel()
        val parsed = EcgCsvParser.parseBytes(recorder.finish(snapshot, "w").gzip, true, "bpm")
        assertThat(parsed.bpmObservations).hasSize(64)
        assertThat(parsed.bpmObservations.last().observedCaptureElapsedMs).isEqualTo(6_300L)
    }

    private fun fillRemaining(
        recorder: EcgSessionRecorder,
        firstIndex: Int,
        firstSequence: Int,
        total: Int = EcgSessionRecorder.EXPECTED_SAMPLES,
    ): Int {
        var index = firstIndex
        var sequence = firstSequence
        while (index < total) {
            val count = minOf(10, total - index)
            recorder.addEcg(batch(index, count, sequence and 0xff))
            index += count
            sequence += 1
        }
        return sequence
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

package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.CaptureSource
import app.galaxyvitals.domain.LiveBpmObservation
import app.galaxyvitals.domain.TimingTrust
import app.galaxyvitals.domain.Wrist
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class EcgCsvV3Test {
    @Test
    fun samsungPreflightHeartRateRoundTripPreservesStatusTimestampAndIbiPairs() {
        val samsung = LiveBpmObservation(
            atSampleIndex = 0,
            observedCaptureElapsedMs = 0,
            status = LiveBpmSummarizer.RELIABLE,
            displayedBpm = 72.0,
            rawBpm = 72.0,
            source = LiveBpmSummarizer.SOURCE_SAMSUNG_HEART_RATE_PREFLIGHT,
            sensorTimestampMs = 1_700_000_001_000L,
            sensorStatus = 1,
            ibiMs = listOf(832, 835),
            ibiStatus = listOf(0, 0),
        )

        val encoded = encodeV3(bpm = listOf(samsung))
        val text = encoded.toString(Charsets.UTF_8)
        assertThat(text).contains("\"sensor_timestamp_ms\":1700000001000")
        assertThat(text).contains("\"sensor_status\":1")
        assertThat(text).contains("\"ibi_ms\":[832,835]")
        assertThat(text).contains("\"ibi_status\":[0,0]")

        val parsed = EcgCsvParser.parseBytes(encoded, gzip = false, sessionIdHint = "samsung-hr")
        assertThat(parsed.bpmObservations).containsExactly(samsung)
        assertThat(parsed.liveBpmMedian).isEqualTo(72.0)
    }

    @Test
    fun samsungHeartRateObservationValidationRejectsInvalidProvenance() {
        val valid = LiveBpmObservation(
            atSampleIndex = 0,
            observedCaptureElapsedMs = 0,
            status = LiveBpmSummarizer.RELIABLE,
            displayedBpm = 72.0,
            rawBpm = 72.0,
            source = LiveBpmSummarizer.SOURCE_SAMSUNG_HEART_RATE_CONTINUOUS,
            sensorTimestampMs = 1_000L,
            sensorStatus = 1,
            ibiMs = listOf(833),
            ibiStatus = listOf(0),
        )

        assertThrows(IllegalArgumentException::class.java) {
            encodeV3(bpm = listOf(valid.copy(ibiStatus = emptyList())))
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodeV3(
                bpm = listOf(
                    valid.copy(
                        ibiMs = listOf(800, 801, 802, 803, 804),
                        ibiStatus = listOf(0, 0, 0, 0, 0),
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodeV3(bpm = listOf(valid.copy(sensorStatus = -10)))
        }
    }

    @Test
    fun v3RoundTripPreservesRawTimestampsBatchGeometryAndBpmObservations() {
        val encoded = encodeV3(
            values = floatArrayOf(-0.12f, -0.11f, 0.4f),
            rawTs = longArrayOf(12_345L, 12_345L, 12_347L),
            seq = intArrayOf(7, 7, 8),
            offset = intArrayOf(0, 1, 0),
            batchSize = intArrayOf(2, 2, 1),
            bpm = listOf(
                LiveBpmObservation(
                    atSampleIndex = 0,
                    observedCaptureElapsedMs = 0,
                    status = "COLLECTING",
                ),
                LiveBpmObservation(
                    atSampleIndex = 2,
                    observedCaptureElapsedMs = 4,
                    status = "RELIABLE",
                    displayedBpm = 72.0,
                    rawBpm = 72.4,
                    source = "APP_ECG_RR",
                    bSqi = 0.91,
                    rrCount = 6,
                    estimateAgeMs = 200L,
                ),
            ),
        )
        val text = encoded.toString(Charsets.UTF_8)
        assertThat(text).contains("\"schema_version\":3")
        assertThat(text).contains("\"format\":\"csv_mv_v3\"")
        assertThat(text).contains("\"timing_trust\":\"SEQUENCE_RECONSTRUCTED\"")
        assertThat(text).contains("\"analysis_clock_source\":\"SAMPLE_INDEX_2MS\"")
        assertThat(text).contains("\"raw_clock_source\":\"SAMSUNG_DATAPOINT_MS\"")
        assertThat(text).contains("\"missing_sample_count_known\":false")
        assertThat(text).contains("rel_ms,sample_index,ecg_raw_mv,flags,hr_bpm,sensor_timestamp_ms_raw,batch_sequence,batch_sample_offset,batch_size")
        assertThat(text).contains("#bpm=")
        assertThat(text).doesNotContain("\"schema_version\":2")

        val parsed = EcgCsvParser.parseBytes(encoded, gzip = false, sessionIdHint = "v3-rt")
        assertThat(parsed.schemaVersion).isEqualTo(3)
        assertThat(parsed.timingTrust).isEqualTo(TimingTrust.SEQUENCE_RECONSTRUCTED)
        assertThat(parsed.missingSampleCountKnown).isFalse()
        assertThat(parsed.samples.map { it.relMs }).containsExactly(0L, 2L, 4L).inOrder()
        assertThat(parsed.samples.map { it.sensorTimestampMsRaw }).containsExactly(12_345L, 12_345L, 12_347L).inOrder()
        assertThat(parsed.samples.map { it.batchSequence }).containsExactly(7, 7, 8).inOrder()
        assertThat(parsed.samples.map { it.batchSampleOffset }).containsExactly(0, 1, 0).inOrder()
        assertThat(parsed.samples.map { it.batchSize }).containsExactly(2, 2, 1).inOrder()
        assertThat(parsed.samples.map { it.hrBpm }).containsExactly(null, null, null)
        assertThat(parsed.bpmObservations).hasSize(2)
        assertThat(parsed.bpmObservations[0].status).isEqualTo("COLLECTING")
        assertThat(parsed.bpmObservations[1].displayedBpm).isEqualTo(72.0)
        assertThat(parsed.bpmObservations[1].source).isEqualTo("APP_ECG_RR")
        assertThat(parsed.bpmObservations[1].bSqi).isEqualTo(0.91)
        assertThat(parsed.bpmObservations[1].rrCount).isEqualTo(6)
        assertThat(parsed.liveBpmMedian).isEqualTo(72.0)
        assertThat(parsed.liveBpmMin).isEqualTo(72.0)
        assertThat(parsed.liveBpmMax).isEqualTo(72.0)
        assertThat(parsed.watchInfo).contains("1.4.1")
        assertThat(parsed.sensorSdk).isEqualTo("1.4.1")
        assertThat(parsed.sensorAarSha256)
            .isEqualTo("893CD5D6564DB0F304BF511A555C1D65CA6BCCC8475FC979FF1D71D50680344C")
    }

    @Test
    fun encodeParsedDoesNotDowngradeV3ToV2() {
        val original = EcgCsvParser.parseBytes(encodeV3(), gzip = false, sessionIdHint = "v3-keep")
        val again = EcgCsvWriter.encodeParsed(original).toString(Charsets.UTF_8)
        assertThat(again).contains("\"schema_version\":3")
        assertThat(again).contains("csv_mv_v3")
        assertThat(again).doesNotContain("\"schema_version\":2")
        assertThat(again).doesNotContain("csv_mv_v2")

        val parsed = EcgCsvParser.parseBytes(again.toByteArray(), gzip = false, sessionIdHint = "v3-keep")
        assertThat(parsed.schemaVersion).isEqualTo(3)
        assertThat(parsed.samples.map { it.sensorTimestampMsRaw })
            .isEqualTo(original.samples.map { it.sensorTimestampMsRaw })
        assertThat(parsed.bpmObservations).isEqualTo(original.bpmObservations)
    }

    @Test
    fun v1AndV2StillParseAndV2EffectiveTrustIsUnverified() {
        val v1 = """
            #meta={"sr_hz":500,"unit":"mV","ts_start":9,"format":"csv_mv","wrist":"LEFT","signFactor":1,"polarityNormalized":true,"watch_info":"w"}
            rel_ms,value_mv,hr_bpm
            0,0.1,60
            2,0.2,
        """.trimIndent()
        val parsedV1 = EcgCsvParser.parseBytes(v1.toByteArray(), gzip = false, sessionIdHint = "v1")
        assertThat(parsedV1.schemaVersion).isEqualTo(1)
        assertThat(parsedV1.timingTrust).isEqualTo(TimingTrust.ASSUMED)
        assertThat(parsedV1.samples).hasSize(2)

        val v2 = EcgCsvWriter.encodeCaptureV2(
            wallStartMs = 1L,
            sensorStartMs = 9L,
            valuesMv = floatArrayOf(0.1f, 0.2f),
            relMs = longArrayOf(0L, 2L),
            sampleFlags = intArrayOf(0, 0),
            wrist = Wrist.LEFT,
            signFactor = 1,
            watchInfo = "legacy-v2",
            captureSource = CaptureSource.HARDWARE,
        )
        val v2Text = v2.toString(Charsets.UTF_8)
        assertThat(v2Text).contains("\"schema_version\":2")
        assertThat(v2Text).contains("\"timing_trust\":\"SENSOR\"")
        assertThat(v2Text).doesNotContain("sensor_timestamp_ms_raw")

        val parsedV2 = EcgCsvParser.parseBytes(v2, gzip = false, sessionIdHint = "v2")
        assertThat(parsedV2.schemaVersion).isEqualTo(2)
        assertThat(parsedV2.timingTrust).isEqualTo(TimingTrust.UNVERIFIED)
        assertThat(parsedV2.samples.map { it.valueMv }).containsExactly(0.1f, 0.2f).inOrder()
        assertThat(parsedV2.samples.map { it.sensorTimestampMsRaw }).containsExactly(null, null)
    }

    @Test
    fun malformedV3IsRejected() {
        val good = encodeV3().toString(Charsets.UTF_8)
        val header = good.lineSequence().first()
        assertThrows(EcgParseException::class.java) {
            EcgCsvParser.parseBytes(
                """
                    $header
                    rel_ms,sample_index,ecg_raw_mv,flags,hr_bpm,sensor_timestamp_ms_raw,batch_sequence,batch_sample_offset,batch_size
                    0,0,0.1,0,,1000,0,0
                """.trimIndent().toByteArray(),
                gzip = false,
                sessionIdHint = "short-row",
            )
        }
        assertThrows(EcgParseException::class.java) {
            parseCustomRows(listOf("4,0,0.1,0,,1000,0,0,2", "6,1,0.2,0,,1000,0,1,2"))
        }
        assertThrows(EcgParseException::class.java) {
            parseCustomRows(listOf("0,0,0.1,0,,1000,0,0,2", "2,1,0.2,0,,999,0,1,2"))
        }
        assertThrows(EcgParseException::class.java) {
            EcgCsvParser.parseBytes(
                good.replace("\"schema_version\":3", "\"schema_version\":4").toByteArray(),
                gzip = false,
                sessionIdHint = "v4",
            )
        }
        assertThrows(EcgParseException::class.java) {
            parseBpmLines(
                (0 until 65).joinToString("\n") { index ->
                    """#bpm={"id":$index,"at_sample_index":$index,"observed_capture_elapsed_ms":${index * 100},"status":"COLLECTING"}"""
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodeV3(
                bpm = List(65) { index ->
                    LiveBpmObservation(
                        atSampleIndex = index.toLong(),
                        observedCaptureElapsedMs = index * 100L,
                        status = "COLLECTING",
                    )
                },
            )
        }
        assertThrows(EcgParseException::class.java) {
            parseBpmLines(
                """
                    #bpm={"id":0,"at_sample_index":0,"observed_capture_elapsed_ms":0,"status":"COLLECTING"}
                    #bpm={"id":2,"at_sample_index":1,"observed_capture_elapsed_ms":2,"status":"COLLECTING"}
                """.trimIndent(),
            )
        }
        assertThrows(EcgParseException::class.java) {
            parseBpmLines(
                """
                    #bpm={"id":0,"at_sample_index":0,"observed_capture_elapsed_ms":10,"status":"COLLECTING"}
                    #bpm={"id":1,"at_sample_index":1,"observed_capture_elapsed_ms":8,"status":"COLLECTING"}
                """.trimIndent(),
            )
        }
        assertThrows(EcgParseException::class.java) {
            parseBpmLines(
                """#bpm={"id":0,"at_sample_index":0,"observed_capture_elapsed_ms":0,"status":"RELIABLE","displayed_bpm":72}""",
            )
        }
    }

    @Test
    fun liveBpmSummaryIsDurationWeightedAndCutsAfterThreeSeconds() {
        val observations = listOf(
            LiveBpmObservation(0, 0, "COLLECTING"),
            LiveBpmObservation(
                atSampleIndex = 500,
                observedCaptureElapsedMs = 1_000,
                status = "RELIABLE",
                displayedBpm = 60.0,
                rawBpm = 60.0,
                source = "APP_ECG_RR",
                bSqi = 0.9,
                rrCount = 5,
                estimateAgeMs = 0,
            ),
            LiveBpmObservation(
                atSampleIndex = 2_000,
                observedCaptureElapsedMs = 4_000,
                status = "RELIABLE",
                displayedBpm = 80.0,
                rawBpm = 80.0,
                source = "APP_ECG_RR",
                bSqi = 0.92,
                rrCount = 6,
                estimateAgeMs = 500,
            ),
            LiveBpmObservation(
                atSampleIndex = 4_000,
                observedCaptureElapsedMs = 8_000,
                status = "UNRELIABLE",
                reasonCode = "LOW_BSQI",
            ),
        )
        val summary = LiveBpmSummarizer.summarize(observations, sessionDurationMs = 10_000L)
        // 1000–4000 at 60 (3000 ms) + 4000–6500 at 80 (2500 ms remaining ttl) = 5500/10000
        assertThat(summary.observationCount).isEqualTo(4)
        assertThat(summary.median).isEqualTo(60.0)
        assertThat(summary.min).isEqualTo(60.0)
        assertThat(summary.max).isEqualTo(80.0)
        assertThat(summary.reliableCoveragePct).isWithin(0.01).of(55.0)
        assertThat(summary.algorithmId).isEqualTo(LiveBpmSummarizer.ALGORITHM_ID)
    }

    @Test
    fun writerRejectsReliableObservationMissingProvenance() {
        assertThrows(IllegalArgumentException::class.java) {
            encodeV3(
                bpm = listOf(
                    LiveBpmObservation(
                        atSampleIndex = 0,
                        observedCaptureElapsedMs = 0,
                        status = "RELIABLE",
                        displayedBpm = 70.0,
                    ),
                ),
            )
        }
    }

    private fun encodeV3(
        values: FloatArray = floatArrayOf(-0.12f, -0.11f),
        rawTs: LongArray = longArrayOf(1_000L, 1_000L),
        seq: IntArray = intArrayOf(0, 0),
        offset: IntArray = intArrayOf(0, 1),
        batchSize: IntArray = intArrayOf(2, 2),
        bpm: List<LiveBpmObservation> = listOf(
            LiveBpmObservation(0, 0, "COLLECTING"),
        ),
    ): ByteArray = EcgCsvWriter.encodeCaptureV3(
        wallStartMs = 1_700_000_000_000L,
        sensorStartMs = rawTs.first(),
        valuesMv = values,
        sampleFlags = IntArray(values.size),
        sensorTimestampsMsRaw = rawTs,
        batchSequence = seq,
        batchSampleOffset = offset,
        batchSize = batchSize,
        wrist = Wrist.RIGHT,
        signFactor = -1,
        watchInfo = """{"sensorSdk":"1.4.1","sensorAarSha256":"893CD5D6564DB0F304BF511A555C1D65CA6BCCC8475FC979FF1D71D50680344C"}""",
        captureSource = CaptureSource.HARDWARE,
        bpmObservations = bpm,
        listenerDurationMs = 30_000L,
        sensorSdk = "1.4.1",
        sensorAarSha256 = "893CD5D6564DB0F304BF511A555C1D65CA6BCCC8475FC979FF1D71D50680344C",
    )

    private fun parseBpmLines(bpmBlock: String): ParsedEcgFile {
        val body = """
            #meta={"schema_version":3,"sr_hz":500,"effective_sr_hz":500,"unit":"mV","ts_start":1,"sensor_start_ms":1000,"format":"csv_mv_v3","capture_source":"HARDWARE","timing_trust":"SEQUENCE_RECONSTRUCTED","analysis_clock_source":"SAMPLE_INDEX_2MS","raw_clock_source":"SAMSUNG_DATAPOINT_MS","raw_timing_trust":"UNVERIFIED","raw_sensor_duration_ms":0,"listener_duration_ms":2,"sample_count":2,"duration_ms":2,"missing_sample_count_known":false,"wrist":"LEFT","signFactor":1,"polarityNormalized":false}
            rel_ms,sample_index,ecg_raw_mv,flags,hr_bpm,sensor_timestamp_ms_raw,batch_sequence,batch_sample_offset,batch_size
            0,0,0.1,0,,1000,0,0,2
            2,1,0.2,0,,1000,0,1,2
            $bpmBlock
        """.trimIndent()
        return EcgCsvParser.parseBytes(body.toByteArray(), gzip = false, sessionIdHint = "bpm-bad")
    }

    private fun parseCustomRows(rows: List<String>): ParsedEcgFile {
        val body = """
            #meta={"schema_version":3,"sr_hz":500,"effective_sr_hz":500,"unit":"mV","ts_start":1,"sensor_start_ms":1000,"format":"csv_mv_v3","capture_source":"HARDWARE","timing_trust":"SEQUENCE_RECONSTRUCTED","analysis_clock_source":"SAMPLE_INDEX_2MS","raw_clock_source":"SAMSUNG_DATAPOINT_MS","raw_timing_trust":"UNVERIFIED","raw_sensor_duration_ms":0,"listener_duration_ms":2,"sample_count":${rows.size},"duration_ms":${(rows.size - 1) * 2},"missing_sample_count_known":false,"wrist":"LEFT","signFactor":1,"polarityNormalized":false}
            rel_ms,sample_index,ecg_raw_mv,flags,hr_bpm,sensor_timestamp_ms_raw,batch_sequence,batch_sample_offset,batch_size
            ${rows.joinToString("\n")}
        """.trimIndent()
        return EcgCsvParser.parseBytes(body.toByteArray(), gzip = false, sessionIdHint = "rows")
    }
}

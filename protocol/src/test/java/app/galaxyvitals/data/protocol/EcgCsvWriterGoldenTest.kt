package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.CaptureSource
import app.galaxyvitals.domain.LiveBpmObservation
import app.galaxyvitals.domain.TimingTrust
import app.galaxyvitals.domain.Wrist
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Byte-exact pins for the watch/phone CSV contract.
 *
 * Encoder output is not merely parseable: it travels the Data Layer and is
 * hashed with SHA-256 for de-duplication and collision detection on the phone,
 * so a reordered metadata key or a changed `null` spelling is a wire break
 * rather than a cosmetic edit. The other writer tests assert that individual
 * keys are *present*; these assert the whole file, character for character, so
 * any restructuring of the emitter has to preserve it.
 */
class EcgCsvWriterGoldenTest {

    @Test
    fun schemaV1CaptureBytesAreExact() {
        val expected = listOf(
            """#meta={"sr_hz":500,"unit":"mV","ts_start":1700000000000,"format":"csv_mv","hr_start_rel_ms":0,"dropped_rows_before_hr":0,"rows_with_hr_pct":66.66666666666667,"watch_info":"{\"model\":\"SM-R9\\\"5\"}","wrist":"RIGHT","signFactor":-1,"polarityNormalized":true}""",
            "rel_ms,value_mv,hr_bpm",
            "0,0.1,",
            "2,-0.25,72",
            "4,0.375,72",
        ).joinToString("\n", postfix = "\n")

        assertThat(encodeV1().toString(Charsets.UTF_8)).isEqualTo(expected)
    }

    @Test
    fun schemaV2CaptureBytesAreExact() {
        val expected = listOf(
            """#meta={"schema_version":2,"sr_hz":500,"effective_sr_hz":500.0,"unit":"mV","ts_start":1700000000000,"sensor_start_ms":4242,"clock_source":"SAMSUNG_DATAPOINT_MS","timing_trust":"SENSOR","format":"csv_mv_v2","capture_source":"HARDWARE","sample_count":3,"duration_ms":4,"gap_count":1,"missing_sample_count":2,"sequence_gap_count":3,"contact_loss_count":4,"clipped_sample_count":5,"acquisition_flags":6,"min_threshold_mv":-3.5,"max_threshold_mv":3.5,"watch_info":"watch\tinfo","wrist":"LEFT","signFactor":1,"polarityNormalized":false}""",
            "rel_ms,sample_index,ecg_raw_mv,flags,hr_bpm",
            "0,0,0.1,0,",
            "2,1,-0.25,1,",
            "4,2,0.375,0,",
        ).joinToString("\n", postfix = "\n")

        assertThat(encodeV2().toString(Charsets.UTF_8)).isEqualTo(expected)
    }

    @Test
    fun schemaV3CaptureBytesAreExact() {
        val expected = listOf(
            """#meta={"schema_version":3,"sr_hz":500,"effective_sr_hz":500.0,"unit":"mV","ts_start":1700000000000,"sensor_start_ms":1000,"format":"csv_mv_v3","capture_source":"HARDWARE","timing_trust":"SEQUENCE_RECONSTRUCTED","analysis_clock_source":"SAMPLE_INDEX_2MS","raw_clock_source":"SAMSUNG_DATAPOINT_MS","raw_timing_trust":"UNVERIFIED","raw_sensor_duration_ms":2,"listener_duration_ms":30000,"sample_count":3,"duration_ms":4,"gap_count":1,"missing_sample_count":2,"missing_sample_count_known":false,"sequence_gap_count":3,"contact_loss_count":4,"clipped_sample_count":5,"acquisition_flags":6,"repeated_timestamp_count":7,"batch_count":2,"min_threshold_mv":-3.5,"max_threshold_mv":3.5,"sensor_sdk":"1.4.1","sensor_aar_sha256":"ABCD","live_bpm_algorithm_id":"app.galaxyvitals.live_bpm.v1","live_bpm_observation_count":2,"live_bpm_median":72.0,"live_bpm_min":72.0,"live_bpm_max":72.0,"live_bpm_reliable_coverage_pct":33.333333333333336,"watch_info":"{\"sensorSdk\":\"1.4.1\"}","wrist":"RIGHT","signFactor":-1,"polarityNormalized":false}""",
            "rel_ms,sample_index,ecg_raw_mv,flags,hr_bpm,sensor_timestamp_ms_raw,batch_sequence,batch_sample_offset,batch_size",
            "0,0,0.1,0,,1000,0,0,2",
            "2,1,-0.25,1,,1000,0,1,2",
            "4,2,0.375,0,,1002,1,0,1",
            """#bpm={"id":0,"at_sample_index":0,"observed_capture_elapsed_ms":0,"status":"COLLECTING","displayed_bpm":null,"raw_bpm":null,"source":null,"b_sqi":null,"rr_count":null,"estimate_age_ms":0,"reason_code":null,"sensor_timestamp_ms":null,"sensor_status":null,"ibi_ms":[],"ibi_status":[]}""",
            """#bpm={"id":1,"at_sample_index":2,"observed_capture_elapsed_ms":4,"status":"RELIABLE","displayed_bpm":72.0,"raw_bpm":72.4,"source":"APP_ECG_RR","b_sqi":0.91,"rr_count":6,"estimate_age_ms":200,"reason_code":"OK","sensor_timestamp_ms":1700000001000,"sensor_status":1,"ibi_ms":[832,835],"ibi_status":[0,0]}""",
        ).joinToString("\n", postfix = "\n")

        assertThat(encodeV3().toString(Charsets.UTF_8)).isEqualTo(expected)
    }

    /**
     * Re-encoding a parsed recording has to reproduce it exactly. The phone
     * rewrites schema-v1 files canonically on ingest, so drift here would change
     * stored bytes - and therefore stored hashes - for existing rows.
     */
    @Test
    fun schemaV1SurvivesParseAndReEncodeUnchanged() {
        assertRoundTripIsByteIdentical(encodeV1(), "golden-v1")
    }

    @Test
    fun schemaV2SurvivesParseAndReEncodeUnchanged() {
        assertRoundTripIsByteIdentical(encodeV2(), "golden-v2")
    }

    @Test
    fun schemaV3SurvivesParseAndReEncodeUnchanged() {
        assertRoundTripIsByteIdentical(encodeV3(), "golden-v3")
    }

    private fun assertRoundTripIsByteIdentical(encoded: ByteArray, sessionId: String) {
        val parsed = EcgCsvParser.parseBytes(encoded, gzip = false, sessionIdHint = sessionId)
        val again = EcgCsvWriter.encodeParsed(parsed)
        assertThat(again.toString(Charsets.UTF_8)).isEqualTo(encoded.toString(Charsets.UTF_8))
    }

    private fun encodeV1(): ByteArray = EcgCsvWriter.encodeCapture(
        sessionStartMs = 1_700_000_000_000L,
        valuesMv = floatArrayOf(0.1f, -0.25f, 0.375f),
        hrStamps = listOf(HrStamp(1_700_000_000_002L, 72)),
        wrist = Wrist.RIGHT,
        signFactor = -1,
        watchInfo = """{"model":"SM-R9\"5"}""",
    )

    private fun encodeV2(): ByteArray = EcgCsvWriter.encodeCaptureV2(
        wallStartMs = 1_700_000_000_000L,
        sensorStartMs = 4_242L,
        valuesMv = floatArrayOf(0.1f, -0.25f, 0.375f),
        relMs = longArrayOf(0L, 2L, 4L),
        sampleFlags = intArrayOf(0, 1, 0),
        wrist = Wrist.LEFT,
        signFactor = 1,
        watchInfo = "watch\tinfo",
        captureSource = CaptureSource.HARDWARE,
        gapCount = 1,
        missingSampleCount = 2,
        sequenceGapCount = 3,
        contactLossCount = 4,
        clippedSampleCount = 5,
        acquisitionFlags = 6,
        minThresholdMv = -3.5f,
        maxThresholdMv = 3.5f,
    )

    private fun encodeV3(): ByteArray = EcgCsvWriter.encodeCaptureV3(
        wallStartMs = 1_700_000_000_000L,
        sensorStartMs = 1_000L,
        valuesMv = floatArrayOf(0.1f, -0.25f, 0.375f),
        sampleFlags = intArrayOf(0, 1, 0),
        sensorTimestampsMsRaw = longArrayOf(1_000L, 1_000L, 1_002L),
        batchSequence = intArrayOf(0, 0, 1),
        batchSampleOffset = intArrayOf(0, 1, 0),
        batchSize = intArrayOf(2, 2, 1),
        wrist = Wrist.RIGHT,
        signFactor = -1,
        watchInfo = """{"sensorSdk":"1.4.1"}""",
        captureSource = CaptureSource.HARDWARE,
        bpmObservations = listOf(
            LiveBpmObservation(0, 0, "COLLECTING"),
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
                reasonCode = "OK",
                sensorTimestampMs = 1_700_000_001_000L,
                sensorStatus = 1,
                ibiMs = listOf(832, 835),
                ibiStatus = listOf(0, 0),
            ),
        ),
        listenerDurationMs = 30_000L,
        gapCount = 1,
        missingSampleCount = 2,
        sequenceGapCount = 3,
        contactLossCount = 4,
        clippedSampleCount = 5,
        acquisitionFlags = 6,
        minThresholdMv = -3.5f,
        maxThresholdMv = 3.5f,
        repeatedTimestampCount = 7,
        batchCount = 2,
        rawTimingTrust = TimingTrust.UNVERIFIED,
        sensorSdk = "1.4.1",
        sensorAarSha256 = "ABCD",
    )
}

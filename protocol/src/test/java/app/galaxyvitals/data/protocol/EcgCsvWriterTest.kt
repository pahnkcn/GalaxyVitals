package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.Wrist
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EcgCsvWriterTest {

    @Test
    fun captureRoundTripNeverDropsRowsForHrAlignment() {
        val start = 1_700_000_000_000L
        // HR is optional side-channel metadata and must never trim the ECG waveform.
        val values = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f)
        val hr = listOf(HrStamp(start + 6L, 72), HrStamp(start + 14L, 80))
        val gz = EcgCsvWriter.encodeCaptureGzip(
            sessionStartMs = start,
            valuesMv = values,
            hrStamps = hr,
            wrist = Wrist.RIGHT,
            signFactor = -1,
            watchInfo = """{"model":"Test"}""",
        )
        val parsed = EcgCsvParser.parseBytes(gz, gzip = true, sessionIdHint = "sid")
        assertThat(parsed.sessionId).isEqualTo("sid")
        assertThat(parsed.tsStartMs).isEqualTo(start)
        assertThat(parsed.wrist.name).isEqualTo("RIGHT")
        assertThat(parsed.signFactor).isEqualTo(-1)
        assertThat(parsed.polarityNormalized).isTrue()
        assertThat(parsed.samples).hasSize(10)
        assertThat(parsed.samples.first().relMs).isEqualTo(0L)
        assertThat(parsed.samples.first().valueMv).isEqualTo(0.1f)
        assertThat(parsed.samples.first().hrBpm).isNull()
        assertThat(parsed.samples[3].hrBpm).isEqualTo(72)
        assertThat(parsed.samples.last().hrBpm).isEqualTo(80)
        assertThat(parsed.watchInfo).contains("Test")
    }

    @Test
    fun noHrKeepsAllRows() {
        val start = 1000L
        val values = floatArrayOf(0.1f, -0.2f, 0.3f)
        val utf8 = EcgCsvWriter.encodeCapture(
            sessionStartMs = start,
            valuesMv = values,
            hrStamps = emptyList(),
            wrist = Wrist.LEFT,
            signFactor = 1,
            watchInfo = "demo",
        )
        val text = utf8.toString(Charsets.UTF_8)
        assertThat(text).contains("\"dropped_rows_before_hr\":0")
        val parsed = EcgCsvParser.parseBytes(utf8, gzip = false, sessionIdHint = "n")
        assertThat(parsed.tsStartMs).isEqualTo(start)
        assertThat(parsed.samples).hasSize(3)
        assertThat(parsed.samples.map { it.hrBpm }).containsExactly(null, null, null)
    }

    @Test
    fun heartRateBeforeCaptureDoesNotMoveStartBackwards() {
        val start = 1_000L
        val encoded = EcgCsvWriter.encodeCapture(
            sessionStartMs = start,
            valuesMv = floatArrayOf(0.1f, 0.2f),
            hrStamps = listOf(HrStamp(start - 100L, 70)),
            wrist = Wrist.LEFT,
            signFactor = 1,
            watchInfo = "test",
        )

        val parsed = EcgCsvParser.parseBytes(encoded, gzip = false, sessionIdHint = "before")

        assertThat(parsed.tsStartMs).isEqualTo(start)
        assertThat(parsed.samples.first().relMs).isEqualTo(0L)
        assertThat(parsed.samples.first().hrBpm).isEqualTo(70)
    }

    @Test
    fun nonGridHeartRateDoesNotMoveCaptureStart() {
        val start = 10_000L
        val encoded = EcgCsvWriter.encodeCapture(
            sessionStartMs = start,
            valuesMv = FloatArray(60) { it / 100f },
            hrStamps = listOf(HrStamp(start + 101L, 72)),
            wrist = Wrist.LEFT,
            signFactor = 1,
            watchInfo = "test",
        )

        val parsed = EcgCsvParser.parseBytes(encoded, gzip = false, sessionIdHint = "grid")

        assertThat(parsed.tsStartMs).isEqualTo(start)
        assertThat(parsed.samples.first().relMs).isEqualTo(0L)
        assertThat(parsed.samples.first().hrBpm).isNull()
        assertThat(parsed.samples[51].hrBpm).isEqualTo(72)
    }

    @Test
    fun duplicateHeartRateTimestampUsesLastValue() {
        val start = 1_000L
        val encoded = EcgCsvWriter.encodeCapture(
            sessionStartMs = start,
            valuesMv = floatArrayOf(0.1f, 0.2f),
            hrStamps = listOf(HrStamp(start, 60), HrStamp(start, 75)),
            wrist = Wrist.LEFT,
            signFactor = 1,
            watchInfo = "test",
        )

        val parsed = EcgCsvParser.parseBytes(encoded, gzip = false, sessionIdHint = "duplicate")

        assertThat(parsed.samples.first().hrBpm).isEqualTo(75)
    }

    @Test
    fun metadataEscapesJsonControlCharacters() {
        val watchInfo = "line 1\nline 2\t\u0001"
        val encoded = EcgCsvWriter.encodeCapture(
            sessionStartMs = 1_000L,
            valuesMv = floatArrayOf(0.1f),
            hrStamps = emptyList(),
            wrist = Wrist.LEFT,
            signFactor = 1,
            watchInfo = watchInfo,
        )

        val parsed = EcgCsvParser.parseBytes(encoded, gzip = false, sessionIdHint = "escaped")

        assertThat(parsed.watchInfo).isEqualTo(watchInfo)
    }

    @Test
    fun parsedRoundTripPreservesRows() {
        val original = EcgCsvParser.parseBytes(
            """
                #meta={"sr_hz":500,"unit":"mV","ts_start":9,"format":"csv_mv","wrist":"LEFT","signFactor":1,"polarityNormalized":true,"watch_info":"w"}
                rel_ms,value_mv,hr_bpm
                0,0.1,60
                2,0.2,
            """.trimIndent().toByteArray(),
            gzip = false,
            sessionIdHint = "p",
        )
        val again = EcgCsvParser.parseBytes(
            EcgCsvWriter.gzipBytes(EcgCsvWriter.encodeParsed(original)),
            gzip = true,
            sessionIdHint = "p",
        )
        assertThat(again.samples.map { it.valueMv }).isEqualTo(original.samples.map { it.valueMv })
        assertThat(again.hrMedian).isEqualTo(60.0)
    }

    @Test
    fun signFactorHelper() {
        assertThat(EcgWearContract.signFactorFor(app.galaxyvitals.domain.Wrist.LEFT)).isEqualTo(1)
        assertThat(EcgWearContract.signFactorFor(app.galaxyvitals.domain.Wrist.RIGHT)).isEqualTo(-1)
    }

    @Test
    fun captureTimingMatchesWatchCompanion() {
        assertThat(EcgWearContract.DEFAULT_SR_HZ).isEqualTo(500)
        assertThat(EcgWearContract.MEASURE_DURATION_MS).isEqualTo(30_000L)
        assertThat(EcgWearContract.OFF_BODY_BLOCK_MS).isEqualTo(1_800L)
        assertThat(EcgWearContract.ECG_STALL_MS).isEqualTo(2_000L)
    }
}

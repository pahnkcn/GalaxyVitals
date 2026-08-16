package app.healthtrack.data.protocol

import app.healthtrack.domain.Wrist
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EcgCsvWriterTest {

    @Test
    fun captureRoundTripAppliesHrAlignment() {
        val start = 1_700_000_000_000L
        // 10 samples at 500 Hz = 2 ms each. First HR arrives 6 ms after start → drop 3 rows.
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
        assertThat(parsed.tsStartMs).isEqualTo(start + 6L)
        assertThat(parsed.wrist.name).isEqualTo("RIGHT")
        assertThat(parsed.signFactor).isEqualTo(-1)
        assertThat(parsed.polarityNormalized).isTrue()
        assertThat(parsed.samples).hasSize(7)
        assertThat(parsed.samples.first().relMs).isEqualTo(0L)
        assertThat(parsed.samples.first().valueMv).isEqualTo(0.4f)
        assertThat(parsed.samples.first().hrBpm).isEqualTo(72)
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
        assertThat(EcgWearContract.signFactorFor(app.healthtrack.domain.Wrist.LEFT)).isEqualTo(1)
        assertThat(EcgWearContract.signFactorFor(app.healthtrack.domain.Wrist.RIGHT)).isEqualTo(-1)
    }
}

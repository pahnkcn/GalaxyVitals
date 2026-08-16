package app.healthtrack.data.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class EcgCsvParserTest {

    private val body = """
        #meta={"sr_hz":500,"unit":"mV","ts_start":1700000000000,"format":"csv_mv","wrist":"LEFT","signFactor":-1,"polarityNormalized":true,"watch_info":"{\"model\":\"Watch7\"}"}
        rel_ms,value_mv,hr_bpm
        0,0.10,70
        2,0.00,70
        4,-0.12,72
        6,1.40,
        8,0.05,NaN
    """.trimIndent()

    @Test
    fun parsesGzipWatchContract() {
        val gz = gzip(body)
        val parsed = EcgCsvParser.parseBytes(gz, gzip = true, sessionIdHint = "abc123")
        assertThat(parsed.sessionId).isEqualTo("abc123")
        assertThat(parsed.srHz).isEqualTo(500)
        assertThat(parsed.unit).isEqualTo("mV")
        assertThat(parsed.tsStartMs).isEqualTo(1700000000000L)
        assertThat(parsed.signFactor).isEqualTo(-1)
        assertThat(parsed.polarityNormalized).isTrue()
        assertThat(parsed.samples).hasSize(5)
        assertThat(parsed.samples[3].hrBpm).isNull()
        assertThat(parsed.hrMin).isEqualTo(70)
        assertThat(parsed.hrMax).isEqualTo(72)
        assertThat(parsed.hrMedian).isEqualTo(70.0)
        assertThat(parsed.durationSec).isEqualTo(0.008)
        assertThat(parsed.watchInfo).contains("Watch7")
    }

    @Test
    fun acceptsSnakeCaseMetaKeys() {
        val text = """
            #meta={"sr_hz":250,"unit":"mV","ts_start":1,"format":"csv_mv","wrist":"RIGHT","sign_factor":1,"polarity_normalized":false}
            rel_ms,value_mv,hr_bpm
            0,0.2,60
            4,0.3,64
        """.trimIndent()
        val parsed = EcgCsvParser.parseBytes(text.toByteArray(), gzip = false, sessionIdHint = "s")
        assertThat(parsed.srHz).isEqualTo(250)
        assertThat(parsed.signFactor).isEqualTo(1)
        assertThat(parsed.wrist.name).isEqualTo("RIGHT")
    }

    @Test
    fun sessionIdFromFileName() {
        assertThat(EcgWearContract.sessionIdFromFileName("ecg_1700.csv.gz")).isEqualTo("1700")
        assertThat(EcgWearContract.sessionIdFromFileName("ecg_x.gz")).isEqualTo("x")
    }

    @Test(expected = EcgParseException::class)
    fun rejectsMissingMeta() {
        EcgCsvParser.parseBytes("rel_ms,value_mv\n0,1".toByteArray(), gzip = false, sessionIdHint = "z")
    }

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }
}

package app.galaxyvitals.data.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
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
        assertThat(EcgWearContract.sessionIdFromFileName("ecg_1700")).isEqualTo("1700")
        assertThat(EcgWearContract.sessionIdFromFileName("1700.csv.gz")).isEqualTo("1700")
    }

    @Test(expected = EcgParseException::class)
    fun rejectsMissingMeta() {
        EcgCsvParser.parseBytes("rel_ms,value_mv\n0,1".toByteArray(), gzip = false, sessionIdHint = "z")
    }

    @Test(expected = EcgParseException::class)
    fun rejectsEmptyFile() {
        EcgCsvParser.parseBytes(ByteArray(0), gzip = false, sessionIdHint = "z")
    }

    @Test(expected = EcgParseException::class)
    fun rejectsHeaderWithNoSamples() {
        val text = """
            #meta={"sr_hz":500,"unit":"mV","ts_start":1}
            rel_ms,value_mv,hr_bpm
        """.trimIndent()
        EcgCsvParser.parseBytes(text.toByteArray(), gzip = false, sessionIdHint = "z")
    }

    @Test
    fun unknownWristBecomesUnknown() {
        val text = """
            #meta={"sr_hz":500,"wrist":"CHEST"}
            rel_ms,value_mv,hr_bpm
            0,0.1,60
            2,0.2,60
        """.trimIndent()
        val parsed = EcgCsvParser.parseBytes(text.toByteArray(), gzip = false, sessionIdHint = "z")
        assertThat(parsed.wrist.name).isEqualTo("UNKNOWN")
    }

    @Test
    fun detectsGzipMagic() {
        assertThat(EcgCsvParser.isGzip(gzip(body))).isTrue()
        assertThat(EcgCsvParser.isGzip("not gzip".toByteArray())).isFalse()
        assertThat(EcgCsvParser.isGzip(ByteArray(0))).isFalse()
    }

    @Test
    fun parseBytesSniffsMisnamedGzip() {
        val gz = gzip(body)
        val parsed = EcgCsvParser.parseBytes(gz, gzip = EcgCsvParser.isGzip(gz), sessionIdHint = "misnamed")
        assertThat(parsed.samples).isNotEmpty()
    }

    @Test
    fun autoStreamParsesPlainAndGzipContent() {
        val plain = EcgCsvParser.parseAutoStream(ByteArrayInputStream(body.toByteArray()), "plain")
        val compressed = EcgCsvParser.parseAutoStream(ByteArrayInputStream(gzip(body)), "compressed")

        assertThat(plain.samples).hasSize(5)
        assertThat(compressed.samples).isEqualTo(plain.samples)
    }

    @Test
    fun peeksButParserRejectsRemovedDemoCaptureSource() {
        val text = """
            #meta={"schema_version":2,"sr_hz":500,"effective_sr_hz":500,"unit":"mV","ts_start":1,"sensor_start_ms":1,"clock_source":"SAMSUNG_DATAPOINT_MS","format":"csv_mv_v2","capture_source":"DEMO","timing_trust":"SENSOR","sample_count":1,"duration_ms":0,"wrist":"LEFT","signFactor":1,"polarityNormalized":false}
            rel_ms,sample_index,value_mv,flags,hr_bpm
            0,0,0.1,0,
        """.trimIndent()
        val compressed = gzip(text)

        assertThat(EcgCsvParser.peekCaptureSourceToken(compressed)).isEqualTo("DEMO")
        assertThrows(EcgParseException::class.java) {
            EcgCsvParser.parseBytes(compressed, gzip = true, sessionIdHint = "removed-demo")
        }
    }

    @Test
    fun rejectsInvalidSampleRatesBeforeAllocating() {
        listOf(-1, 0, 2001, Int.MAX_VALUE).forEach { srHz ->
            val text = """
                #meta={"sr_hz":$srHz,"ts_start":1}
                rel_ms,value_mv,hr_bpm
                0,0.1,60
            """.trimIndent()

            assertThrows(EcgParseException::class.java) {
                EcgCsvParser.parseAutoStream(ByteArrayInputStream(text.toByteArray()), "rate")
            }
        }
    }

    @Test
    fun rejectsNegativeDecreasingAndOverlongTimestamps() {
        listOf(
            listOf(-1L, 0L),
            listOf(2L, 1L),
            listOf(0L, EcgCsvParser.MAX_DURATION_MS + 1L),
        ).forEach { timestamps ->
            val rows = timestamps.joinToString("\n") { "$it,0.1,60" }
            val text = """
                #meta={"sr_hz":500,"ts_start":1}
                rel_ms,value_mv,hr_bpm
                $rows
            """.trimIndent()

            assertThrows(EcgParseException::class.java) {
                EcgCsvParser.parseAutoStream(ByteArrayInputStream(text.toByteArray()), "time")
            }
        }
    }

    @Test
    fun rejectsHugeInitialRelativeTimestamp() {
        val text = """
            #meta={"sr_hz":500,"ts_start":1}
            rel_ms,value_mv,hr_bpm
            ${Long.MAX_VALUE - 1},0.1,60
            ${Long.MAX_VALUE},0.2,60
        """.trimIndent()

        assertThrows(EcgParseException::class.java) {
            EcgCsvParser.parseAutoStream(ByteArrayInputStream(text.toByteArray()), "huge-time")
        }
    }

    @Test
    fun acceptsDuplicateMillisecondTimestampsAtHighSampleRates() {
        val text = """
            #meta={"sr_hz":2000,"ts_start":1}
            rel_ms,value_mv,hr_bpm
            0,0.1,60
            0,0.2,61
            1,0.3,62
        """.trimIndent()

        val parsed = EcgCsvParser.parseAutoStream(ByteArrayInputStream(text.toByteArray()), "high-rate")

        assertThat(parsed.samples.map { it.relMs }).containsExactly(0L, 0L, 1L).inOrder()
    }

    @Test
    fun rejectsNegativeStartTimestamp() {
        val text = """
            #meta={"sr_hz":500,"ts_start":-1}
            rel_ms,value_mv,hr_bpm
            0,0.1,60
        """.trimIndent()

        assertThrows(EcgParseException::class.java) {
            EcgCsvParser.parseAutoStream(ByteArrayInputStream(text.toByteArray()), "time")
        }
    }

    @Test
    fun ignoresReservedKeyTextEmbeddedInsideWatchInfo() {
        val text = """
            #meta={"watch_info":"{\"sr_hz\":2001}","ts_start":1}
            rel_ms,value_mv,hr_bpm
            0,0.1,60
        """.trimIndent()

        val parsed = EcgCsvParser.parseAutoStream(ByteArrayInputStream(text.toByteArray()), "embedded")

        assertThat(parsed.srHz).isEqualTo(EcgWearContract.DEFAULT_SR_HZ)
        assertThat(parsed.watchInfo).contains("\"sr_hz\":2001")
    }

    @Test
    fun rejectsMalformedOrAmbiguousMetadataJson() {
        listOf(
            "{\"sr_hz\":500",
            "{\"sr_hz\":500,}",
            "{\"sr_hz\":500} trailing",
            "{\"sr_hz\":500,\"sr_hz\":250}",
            "{\"signFactor\":1,\"sign_factor\":-1}",
            "{\"polarityNormalized\":true,\"polarity_normalized\":false}",
            "{\"polarityNormalized\":TRUE}",
        ).forEach { metadata ->
            val text = """
                #meta=$metadata
                rel_ms,value_mv,hr_bpm
                0,0.1,60
            """.trimIndent()

            assertThrows(EcgParseException::class.java) {
                EcgCsvParser.parseAutoStream(ByteArrayInputStream(text.toByteArray()), "metadata")
            }
        }
    }

    @Test
    fun rejectsNonFiniteAmplitudes() {
        listOf("NaN", "Infinity", "-Infinity", "1e100").forEach { amplitude ->
            val text = """
                #meta={"sr_hz":500,"ts_start":1}
                rel_ms,value_mv,hr_bpm
                0,$amplitude,60
            """.trimIndent()

            assertThrows(EcgParseException::class.java) {
                EcgCsvParser.parseAutoStream(ByteArrayInputStream(text.toByteArray()), "finite")
            }
        }
    }

    @Test
    fun invalidHeartRatesAreTreatedAsMissing() {
        val text = """
            #meta={"sr_hz":500,"ts_start":1}
            rel_ms,value_mv,hr_bpm
            0,0.1,19
            1,0.1,301
            2,0.1,bad
            3,0.1,NaN
            4,0.1,20
            5,0.1,300
        """.trimIndent()

        val parsed = EcgCsvParser.parseAutoStream(ByteArrayInputStream(text.toByteArray()), "hr")

        assertThat(parsed.samples.map { it.hrBpm })
            .containsExactly(null, null, null, null, 20, 300)
            .inOrder()
    }

    @Test
    fun rejectsUnsupportedUnitsFormatsAndPolarityFactors() {
        val metadata = listOf(
            "{\"sr_hz\":500,\"unit\":\"V\",\"ts_start\":1}",
            "{\"sr_hz\":500,\"format\":\"binary\",\"ts_start\":1}",
            "{\"sr_hz\":500,\"signFactor\":0,\"ts_start\":1}",
        )

        metadata.forEach { meta ->
            val text = "#meta=$meta\nrel_ms,value_mv,hr_bpm\n0,0.1,60\n"
            assertThrows(EcgParseException::class.java) {
                EcgCsvParser.parseAutoStream(ByteArrayInputStream(text.toByteArray()), "metadata")
            }
        }
    }

    @Test
    fun rejectsMoreThanMaximumSamples() {
        val text = buildString {
            append("#meta={\"sr_hz\":500,\"ts_start\":1}\n")
            append("rel_ms,value_mv,hr_bpm\n")
            repeat(EcgCsvParser.MAX_SAMPLES + 1) { index ->
                append(index).append(",0.1,60\n")
            }
        }

        val error = assertThrows(EcgParseException::class.java) {
            EcgCsvParser.parseAutoStream(ByteArrayInputStream(text.toByteArray()), "samples")
        }
        assertThat(error).hasMessageThat().contains("samples")
    }

    @Test
    fun rejectsCompressedInputLargerThanLimit() {
        val bytes = ByteArray(EcgCsvParser.MAX_COMPRESSED_BYTES.toInt() + 1)

        val error = assertThrows(EcgParseException::class.java) {
            EcgCsvParser.parseBytes(bytes, gzip = false, sessionIdHint = "large")
        }
        assertThat(error).hasMessageThat().contains("compressed")
    }

    @Test
    fun rejectsGzipThatExpandsPastUncompressedLimit() {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzip ->
            gzip.write(
                "#meta={\"sr_hz\":500,\"ts_start\":1}\nrel_ms,value_mv,hr_bpm\n0,0.1,60\n"
                    .toByteArray(),
            )
            val padding = "# padding\n".toByteArray()
            var written = 0L
            while (written <= EcgCsvParser.MAX_UNCOMPRESSED_BYTES) {
                gzip.write(padding)
                written += padding.size
            }
        }

        val error = assertThrows(EcgParseException::class.java) {
            EcgCsvParser.parseAutoStream(ByteArrayInputStream(out.toByteArray()), "bomb")
        }
        assertThat(error).hasMessageThat().contains("uncompressed")
    }

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }
}

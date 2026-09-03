package app.galaxyvitals.data.protocol.xlsx

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

class XlsxWriterTest {

    @Test
    fun writesEveryPartAnOoxmlReaderRequires() {
        val parts = partsOf(
            build {
                sheet("Summary") { textRow("a") }
                sheet("Samples") { textRow("b") }
            },
        )

        assertThat(parts.keys).containsAtLeast(
            "[Content_Types].xml",
            "_rels/.rels",
            "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels",
            "xl/styles.xml",
            "xl/worksheets/sheet1.xml",
            "xl/worksheets/sheet2.xml",
        )
        assertThat(parts.getValue("xl/workbook.xml")).contains("name=\"Summary\"")
        assertThat(parts.getValue("xl/workbook.xml")).contains("name=\"Samples\"")
        assertThat(parts.getValue("[Content_Types].xml")).contains("/xl/worksheets/sheet2.xml")
    }

    @Test
    fun rowAndCellCountsMatchWhatWasWritten() {
        val sheet = sheetXml(
            build {
                sheet("Samples") {
                    headerRow("time_ms", "raw_mv", "filtered_mv")
                    repeat(15_000) { index ->
                        row {
                            integer(index * 2L)
                            number(0.125, decimals = 6)
                            number(-0.125, decimals = 6)
                        }
                    }
                }
            },
        )

        assertThat(occurrences(sheet, "<row ")).isEqualTo(15_001)
        assertThat(occurrences(sheet, "<c ")).isEqualTo(15_001 * 3)
        assertThat(sheet).contains("r=\"A1\"")
        assertThat(sheet).contains("r=\"C15001\"")
        assertThat(sheet).contains("<v>0.125000</v>")
        assertThat(sheet).contains("<v>-0.125000</v>")
    }

    @Test
    fun numbersUseADotDecimalEvenWhenTheDeviceLocaleDoesNot() {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val sheet = sheetXml(build { sheet("S") { row { number(1234.5, decimals = 2) } } })

            assertThat(sheet).contains("<v>1234.50</v>")
            assertThat(sheet).doesNotContain("1234,50")
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun textIsEscapedAndThaiSurvivesTheRoundTrip() {
        val sheet = sheetXml(
            build {
                sheet("S") {
                    textRow("R&D <peak> \"note\"", "อัตราการเต้นของหัวใจ")
                }
            },
        )

        assertThat(sheet).contains("R&amp;D &lt;peak&gt; \"note\"")
        assertThat(sheet).contains("อัตราการเต้นของหัวใจ")
        assertThat(sheet).doesNotContain("<peak>")
    }

    @Test
    fun controlCharactersXmlForbidsAreDropped() {
        val sheet = sheetXml(build { sheet("S") { textRow("ok\u0000\u0007still\tfine") } })

        assertThat(sheet).contains("okstill\tfine")
    }

    @Test
    fun nullAndNonFiniteValuesWriteAnEmptyCellRatherThanCorruptOne() {
        val sheet = sheetXml(
            build {
                sheet("S") {
                    row {
                        number(Double.NaN)
                        number(Double.POSITIVE_INFINITY)
                        number(null)
                        integer(null as Int?)
                        text(null)
                    }
                }
            },
        )

        assertThat(occurrences(sheet, "<v>")).isEqualTo(0)
        assertThat(occurrences(sheet, "<c ")).isEqualTo(5)
        assertThat(sheet).contains("r=\"E1\"")
    }

    @Test
    fun booleansAndSkippedColumnsKeepCellReferencesAligned() {
        val sheet = sheetXml(
            build {
                sheet("S") {
                    row {
                        boolean(true)
                        skip()
                        boolean(false)
                    }
                }
            },
        )

        assertThat(sheet).contains("<c r=\"A1\" t=\"b\"><v>1</v></c>")
        assertThat(sheet).contains("<c r=\"C1\" t=\"b\"><v>0</v></c>")
        assertThat(sheet).doesNotContain("r=\"B1\"")
    }

    @Test
    fun columnNamesRollOverPastZ() {
        assertThat(XlsxWriter.columnName(1)).isEqualTo("A")
        assertThat(XlsxWriter.columnName(26)).isEqualTo("Z")
        assertThat(XlsxWriter.columnName(27)).isEqualTo("AA")
        assertThat(XlsxWriter.columnName(52)).isEqualTo("AZ")
        assertThat(XlsxWriter.columnName(703)).isEqualTo("AAA")
    }

    @Test
    fun sheetNamesAreTrimmedToWhatExcelAccepts() {
        val workbook = partsOf(
            build { sheet("Raw/Samples[2025]:*?") { textRow("x") } },
        ).getValue("xl/workbook.xml")

        assertThat(workbook).contains("name=\"RawSamples2025\"")
    }

    private fun build(body: XlsxWriter.Workbook.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        XlsxWriter.write(out, body)
        return out.toByteArray()
    }

    private fun sheetXml(bytes: ByteArray): String =
        partsOf(bytes).getValue("xl/worksheets/sheet1.xml")

    private fun partsOf(bytes: ByteArray): Map<String, String> {
        val parts = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                parts[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return parts
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count += 1
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }
}

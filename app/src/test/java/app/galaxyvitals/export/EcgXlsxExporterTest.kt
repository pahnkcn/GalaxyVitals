package app.galaxyvitals.export

import app.galaxyvitals.analysis.EcgAnalysisFixtures
import app.galaxyvitals.data.protocol.QualityFlag
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

class EcgXlsxExporterTest {

    private val report = EcgReportBuilder.build(
        parsed = EcgAnalysisFixtures.clean72BpmRecording(),
        session = null,
        appVersion = "test",
    )

    @Test
    fun theWorkbookCarriesTheThreeSheetsAnalysisNeeds() {
        val parts = partsOf(write(report))

        assertThat(parts.getValue("xl/workbook.xml")).contains("name=\"Summary\"")
        assertThat(parts.getValue("xl/workbook.xml")).contains("name=\"Samples\"")
        assertThat(parts.getValue("xl/workbook.xml")).contains("name=\"Beats\"")
    }

    @Test
    fun everyStoredSampleIsExportedRawBesideItsFilteredValue() {
        val samples = partsOf(write(report)).getValue("xl/worksheets/sheet2.xml")

        // One header row plus one row per stored sample: nothing is downsampled.
        assertThat(occurrences(samples, "<row ")).isEqualTo(report.header.sampleCount + 1)
        val first = report.rawSamples.first().valueMv.toDouble()
        assertThat(samples).contains(String.format(Locale.ROOT, "%.6f", first))
    }

    @Test
    fun beatSheetHoldsAsManyRowsAsTheLongestSeries() {
        val beats = partsOf(write(report)).getValue("xl/worksheets/sheet3.xml")
        val longest = maxOf(
            report.beats.rPeaksMs.size,
            report.beats.rrAllMs.size,
            report.beats.rrNnMs.size,
        )

        assertThat(occurrences(beats, "<row ")).isEqualTo(longest + 1)
        assertThat(longest).isGreaterThan(0)
    }

    @Test
    fun summaryReportsTheSameNumbersTheScreenShows() {
        val summary = partsOf(write(report)).getValue("xl/worksheets/sheet1.xml")
        val rate = report.measurement(MeasurementKey.HEART_RATE)!!

        assertThat(summary).contains("HEART_RATE")
        assertThat(summary).contains("<v>${String.format(Locale.ROOT, "%.0f", rate.value!!)}</v>")
        assertThat(summary).contains("RECORDED_AT")
        assertThat(summary).contains("SESSION_ID")
    }

    @Test
    fun aMeasurementWithNoValueExportsItsReasonInsteadOfABlank() {
        val flat = EcgReportBuilder.build(
            parsed = EcgAnalysisFixtures.lowQualityRecording(),
            session = null,
            appVersion = "test",
        )

        val summary = partsOf(write(flat)).getValue("xl/worksheets/sheet1.xml")

        assertThat(flat.measurement(MeasurementKey.HEART_RATE)!!.isAvailable).isFalse()
        assertThat(summary).contains("UNAVAILABLE:")
    }

    @Test
    fun cellsUseADotDecimalEvenWhenTheDeviceLocaleDoesNot() {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val samples = partsOf(write(report)).getValue("xl/worksheets/sheet2.xml")

            assertThat(samples).doesNotContain(",000000")
            assertThat(samples).contains(".")
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun sampleFlagsAreNamedRatherThanLeftAsABitmask() {
        assertThat(EcgXlsxExporter.flagNames(0)).isEmpty()
        assertThat(EcgXlsxExporter.flagNames(app.galaxyvitals.domain.EcgSampleFlags.CLIPPED))
            .isEqualTo("CLIPPED")
        val both = app.galaxyvitals.domain.EcgSampleFlags.CLIPPED or
            app.galaxyvitals.domain.EcgSampleFlags.CONTACT_LOSS
        assertThat(EcgXlsxExporter.flagNames(both)).isEqualTo("CONTACT_LOSS|CLIPPED")
    }

    private fun write(model: EcgReportModel): ByteArray {
        val out = ByteArrayOutputStream()
        EcgXlsxExporter.write(out, model, EnumNameText)
        return out.toByteArray()
    }

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

    /** Wording stands in as the enum name, so the test pins structure, not copy. */
    private object EnumNameText : EcgReportText {
        override fun section(section: ReportSection) = section.name
        override fun field(field: ReportField) = field.name
        override fun measurement(key: MeasurementKey) = key.name
        override fun unit(key: MeasurementKey) = "unit"
        override fun availability(value: MetricAvailability) = "UNAVAILABLE:${value.name}"
        override fun qualityFlag(flag: QualityFlag) = flag.name
        override fun verdictTitle(verdict: ReportVerdict) = verdict.analysisStatus.name
        override fun verdictBody(verdict: ReportVerdict) = verdict.rateStatus.name
        override fun qualityStatus(quality: ReportQuality) = quality.status.name
        override fun disclaimer() = "DISCLAIMER"
        override fun timestamp(epochMs: Long) = epochMs.toString()
        override fun wrist(header: ReportHeader) = header.wrist.name
        override fun captureSource(header: ReportHeader) = header.captureSource
        override fun scale(header: ReportHeader) = "25/10"
        override fun filter(header: ReportHeader) = header.bandwidth.name
        override fun notMeasured() = "NONE"
    }
}

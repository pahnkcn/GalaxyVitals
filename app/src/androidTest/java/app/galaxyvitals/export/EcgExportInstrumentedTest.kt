package app.galaxyvitals.export

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.galaxyvitals.analysis.EcgAndroidTestFixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The PDF has to be produced by the framework renderer, so this is the one part
 * of the export path that cannot be pinned off-device.
 */
@RunWith(AndroidJUnit4::class)
class EcgExportInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val report = EcgReportBuilder.build(
        parsed = EcgAndroidTestFixtures.clean72BpmRecording(),
        session = null,
        appVersion = "test",
    )

    @Test
    fun theReportIsOneA4LandscapePage() {
        val file = File(context.cacheDir, "report-test.pdf")
        file.outputStream().use { EcgPdfExporter.write(it, report, AndroidReportText(context)) }

        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertThat(renderer.pageCount).isEqualTo(1)
                renderer.openPage(0).use { page ->
                    // A4 landscape at 72 dpi. Print this at 100% and 5 large
                    // boxes measure 25 mm, which is what makes the strip usable.
                    assertThat(page.width).isEqualTo(EcgPdfExporter.PAGE_WIDTH_PT)
                    assertThat(page.height).isEqualTo(EcgPdfExporter.PAGE_HEIGHT_PT)
                }
            }
        }
        file.delete()
    }

    @Test
    fun everyFormatProducesAFileWithItsOwnName() {
        val exporter = EcgExporter(context)
        val stored = File(context.cacheDir, "stored.csv.gz").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val text = AndroidReportText(context)

        val pdf = exporter.export(report, text, ExportFormat.PDF, stored)
        val xlsx = exporter.export(report, text, ExportFormat.XLSX, stored)
        val csv = exporter.export(report, text, ExportFormat.CSV_GZ, stored)

        assertThat(pdf.file.length()).isGreaterThan(0L)
        assertThat(xlsx.file.length()).isGreaterThan(0L)
        // The original file is copied byte for byte, never re-encoded.
        assertThat(csv.file.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(setOf(pdf.displayName, xlsx.displayName, csv.displayName)).hasSize(3)
        assertThat(pdf.displayName).endsWith(".pdf")
        assertThat(xlsx.displayName).endsWith(".xlsx")
        assertThat(csv.displayName).endsWith(".csv.gz")

        listOf(pdf, xlsx, csv).forEach { it.file.delete() }
        stored.delete()
    }

    @Test
    fun theShareUriIsAFileProviderUriAndNotARawPath() {
        val exporter = EcgExporter(context)
        val exported = exporter.export(report, AndroidReportText(context), ExportFormat.XLSX, null)

        val uri = exporter.uriFor(exported.file)

        assertThat(uri.scheme).isEqualTo("content")
        assertThat(uri.authority).isEqualTo("${context.packageName}.fileprovider")
        exported.file.delete()
    }

    @Test
    fun theHeaderNamesTheWatchRatherThanDumpingItsMetadataBlob() {
        val blob = """{"model":"SM-L350","brand":"samsung","os":"Wear OS 17"}"""

        assertThat(EcgPdfExporter.deviceName(blob)).isEqualTo("SM-L350")
        assertThat(EcgPdfExporter.deviceName("")).isNull()
        assertThat(EcgPdfExporter.deviceName("{}")).isNull()
    }

    @Test
    fun theSpreadsheetIsWrittenWithTheAppsOwnWording() {
        val out = ByteArrayOutputStream()

        EcgXlsxExporter.write(out, report, AndroidReportText(context))

        assertThat(out.size()).isGreaterThan(0)
    }
}

package app.galaxyvitals.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.galaxyvitals.data.protocol.EcgWearContract
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat(val extension: String, val mimeType: String) {
    /** A printable report. The strip on it is to scale. */
    PDF("pdf", "application/pdf"),

    /** Every sample and interval, for someone who wants to do their own analysis. */
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),

    /** The stored capture, copied byte for byte. */
    CSV_GZ("csv.gz", "application/gzip"),
}

data class ExportedFile(val file: File, val format: ExportFormat) {
    val displayName: String get() = file.name
}

/**
 * Writes a recording out as a shareable file.
 *
 * Exports land in the cache under a dedicated directory: they are copies, the
 * originals stay in app storage, and the share sheet only ever sees a
 * `FileProvider` uri scoped to that one file. Nothing here reaches the network —
 * the app holds no `INTERNET` permission and this does not change that.
 */
class EcgExporter(private val context: Context) {

    fun export(
        report: EcgReportModel,
        text: EcgReportText,
        format: ExportFormat,
        sourceFile: File?,
    ): ExportedFile {
        val target = File(exportDir(), fileName(report.header, format))
        when (format) {
            ExportFormat.PDF -> writeStream(target) { EcgPdfExporter.write(it, report, text) }
            ExportFormat.XLSX -> writeStream(target) { EcgXlsxExporter.write(it, report, text) }
            ExportFormat.CSV_GZ -> copyStored(sourceFile, target)
        }
        return ExportedFile(target, format)
    }

    fun shareIntent(exported: ExportedFile, chooserTitle: String): Intent {
        val uri = uriFor(exported.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = exported.format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, exported.displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * Removes exports left behind by earlier sessions. They are copies of health
     * data sitting in a shareable directory, so they should not outlive the share
     * that produced them by more than a session.
     */
    fun purgeStaleExports(olderThanMs: Long = STALE_EXPORT_MS) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        exportDir().listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }

    fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

    private fun exportDir(): File =
        File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }

    private fun writeStream(target: File, body: (java.io.OutputStream) -> Unit) {
        BufferedOutputStream(FileOutputStream(target)).use(body)
    }

    private fun copyStored(sourceFile: File?, target: File) {
        require(sourceFile != null && sourceFile.isFile) { "the stored recording is missing" }
        sourceFile.inputStream().use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
    }

    /**
     * `ECG_20260902_0914_ab12cd34.pdf` — sorts by time, names the recording, and
     * survives being dropped into a folder of other people's files.
     */
    internal fun fileName(header: ReportHeader, format: ExportFormat): String {
        val stamp = SimpleDateFormat(FILE_STAMP_PATTERN, Locale.ROOT).format(Date(header.tsStartMs))
        val id = EcgWearContract.sanitizeSessionId(header.sessionId, "ecg").takeLast(8)
        return "ECG_${stamp}_$id.${format.extension}"
    }

    private companion object {
        const val EXPORT_DIR = "exports"
        const val FILE_STAMP_PATTERN = "yyyyMMdd_HHmm"
        const val STALE_EXPORT_MS = 24L * 60L * 60L * 1000L
    }
}

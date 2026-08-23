package app.galaxyvitals.wear.store

import android.content.Context
import app.galaxyvitals.data.protocol.EcgCsvParser
import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.data.protocol.ParsedEcgFile
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class WatchEcgStore internal constructor(private val dir: File) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, EcgWearContract.WATCH_DIR),
    )

    init {
        check(dir.mkdirs() || dir.isDirectory) { "Unable to create ECG store: ${dir.path}" }
        purgeDemoRecordings()
    }

    fun fileFor(sessionId: String): File =
        File(dir, EcgWearContract.inboxFileName(normalizeSessionId(sessionId)))

    fun save(sessionId: String, gzip: ByteArray): File {
        require(gzip.isNotEmpty()) { "ECG payload must not be empty" }
        require(gzip.size <= MAX_GZIP_BYTES) {
            "ECG payload exceeds the $MAX_GZIP_BYTES byte limit"
        }
        check(dir.mkdirs() || dir.isDirectory) { "Unable to create ECG store: ${dir.path}" }

        val dest = fileFor(sessionId)
        val temp = File.createTempFile(".${dest.name}.", TEMP_SUFFIX, dir)
        try {
            FileOutputStream(temp).use { output ->
                output.write(gzip)
                output.flush()
                output.fd.sync()
            }

            // Replacing a session makes it pending again until a matching phone ack arrives.
            syncedMarkerFor(dest).delete()
            moveAtomically(temp, dest)
            return dest
        } finally {
            temp.delete()
        }
    }

    fun delete(sessionId: String): Boolean {
        val file = fileFor(sessionId)
        val marker = syncedMarkerFor(file)
        val existed = file.exists() || marker.exists()
        val fileDeleted = !file.exists() || file.delete()
        val markerDeleted = !marker.exists() || marker.delete()
        return existed && fileDeleted && markerDeleted
    }

    /** Removes every local recording and acknowledgement marker. */
    fun deleteAll(): Int {
        var removed = 0
        listGzipFiles().forEach { file ->
            val sessionId = sessionIdFromCanonicalFile(file) ?: return@forEach
            if (delete(sessionId)) removed += 1
        }
        return removed
    }

    /** Marks only the recording named by the phone acknowledgement as synchronized. */
    fun markSynced(sessionId: String): Boolean {
        val file = fileFor(sessionId)
        if (!file.isFile) return false
        val marker = syncedMarkerFor(file)
        return marker.isFile || marker.createNewFile()
    }

    /** Files without an exact per-session acknowledgement are eligible for upload. */
    fun listPendingGzipFiles(): List<File> = listGzipFiles().filterNot(::isSynced)

    /**
     * Keeps the newest local history and removes only older recordings acknowledged by the phone.
     * Pending files are never pruned, even if they are older than the history window.
     */
    fun pruneAcknowledgedHistory() {
        listGzipFiles()
            .drop(KEEP_AFTER_SYNC)
            .filter(::isSynced)
            .forEach { file ->
                if (file.delete()) syncedMarkerFor(file).delete()
            }
    }

    fun listGzipFiles(): List<File> =
        dir.listFiles { file -> file.isFile && sessionIdFromCanonicalFile(file) != null }
            ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .orEmpty()

    fun parseAll(): List<ParsedEcgFile> = listGzipFiles().mapNotNull { file ->
        runCatching {
            EcgCsvParser.parseFile(file, sessionIdFromCanonicalFile(file)!!)
        }.getOrNull()
    }

    fun latest(): ParsedEcgFile? = parseAll().firstOrNull()

    /** Removes only recordings explicitly labelled DEMO; unlabelled legacy captures are preserved. */
    fun purgeDemoRecordings(): Int {
        var removed = 0
        listGzipFiles().forEach { file ->
            if (EcgCsvParser.peekCaptureSourceToken(file) == "DEMO") {
                val marker = syncedMarkerFor(file)
                val existed = file.exists() || marker.exists()
                val fileDeleted = !file.exists() || file.delete()
                val markerDeleted = !marker.exists() || marker.delete()
                if (existed && fileDeleted && markerDeleted) removed += 1
            }
        }
        return removed
    }

    private fun isSynced(file: File): Boolean = syncedMarkerFor(file).isFile

    private fun syncedMarkerFor(file: File): File = File(file.parentFile, file.name + SYNCED_SUFFIX)

    private fun sessionIdFromCanonicalFile(file: File): String? {
        val name = file.name
        if (!name.startsWith(EcgWearContract.FILE_PREFIX) ||
            !name.endsWith(EcgWearContract.FILE_SUFFIX)
        ) {
            return null
        }
        val id = runCatching { EcgWearContract.sessionIdFromFileName(name) }.getOrNull()
            ?: return null
        return id.takeIf { EcgWearContract.inboxFileName(it) == name }
    }

    private fun normalizeSessionId(value: String): String {
        require(value.isNotBlank() && value == File(value).name &&
            !value.contains('/') && !value.contains('\\')) {
            "Invalid ECG session id"
        }
        return EcgWearContract.sessionIdFromFileName(value)
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    companion object {
        const val KEEP_AFTER_SYNC = 8
        const val MAX_GZIP_BYTES = 4 * 1024 * 1024
        const val SYNCED_SUFFIX = ".synced"
        private const val TEMP_SUFFIX = ".tmp"
    }
}

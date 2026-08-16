package app.healthtrack.wear.store

import android.content.Context
import app.healthtrack.data.protocol.EcgCsvParser
import app.healthtrack.data.protocol.EcgWearContract
import app.healthtrack.data.protocol.ParsedEcgFile
import java.io.File

class WatchEcgStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, EcgWearContract.WATCH_DIR).apply { mkdirs() }

    fun fileFor(sessionId: String): File = File(dir, EcgWearContract.inboxFileName(sessionId))

    fun save(sessionId: String, gzip: ByteArray): File {
        dir.mkdirs()
        val dest = fileFor(sessionId)
        dest.writeBytes(gzip)
        return dest
    }

    fun delete(sessionId: String): Boolean {
        val id = EcgWearContract.sessionIdFromFileName(sessionId)
        val file = fileFor(id)
        return file.exists() && file.delete()
    }

    fun listGzipFiles(): List<File> {
        return dir.listFiles { f -> f.isFile && f.name.endsWith(EcgWearContract.FILE_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    fun parseAll(): List<ParsedEcgFile> = listGzipFiles().mapNotNull { file ->
        runCatching {
            EcgCsvParser.parseFile(file, EcgWearContract.sessionIdFromFileName(file.name))
        }.getOrNull()
    }

    fun latest(): ParsedEcgFile? = parseAll().firstOrNull()
}

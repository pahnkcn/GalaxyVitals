package app.healthtrack.data

import android.content.Context
import android.net.Uri
import app.healthtrack.data.local.AppDatabase
import app.healthtrack.data.local.EcgSessionEntity
import app.healthtrack.data.protocol.EcgCsvParser
import app.healthtrack.data.protocol.EcgCsvWriter
import app.healthtrack.data.protocol.EcgWearContract
import app.healthtrack.data.protocol.ParsedEcgFile
import app.healthtrack.domain.EcgSample
import app.healthtrack.domain.EcgSession
import app.healthtrack.domain.EcgSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class EcgRepository(
    private val context: Context,
    db: AppDatabase,
) {
    private val dao = db.ecgSessionDao()

    fun observeSessions(): Flow<List<EcgSession>> = dao.observeAll().map { rows ->
        rows.map { it.toDomain() }
    }

    fun observeLatest(): Flow<EcgSession?> = dao.observeLatest().map { it?.toDomain() }

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun get(sessionId: String): EcgSession? = dao.get(sessionId)?.toDomain()

    suspend fun loadSamples(session: EcgSession): List<EcgSample> = withContext(Dispatchers.IO) {
        val file = File(session.filePath)
        if (!file.exists()) return@withContext emptyList()
        EcgCsvParser.parseFile(file, session.sessionId).samples
    }

    suspend fun importUri(uri: Uri): EcgSession = withContext(Dispatchers.IO) {
        val name = queryDisplayName(uri) ?: "ecg_import.csv.gz"
        val sessionId = EcgWearContract.sessionIdFromFileName(name)
        val gzip = name.endsWith(".gz", ignoreCase = true)
        context.contentResolver.openInputStream(uri)?.use { input ->
            val parsed = EcgCsvParser.parseStream(input, gzip, sessionId)
            persist(parsed, EcgSource.IMPORT)
        } ?: throw IllegalStateException("Unable to open $uri")
    }

    suspend fun importDemo(): EcgSession = withContext(Dispatchers.IO) {
        val inbox = File(context.filesDir, EcgWearContract.INBOX_DIR).apply { mkdirs() }
        val dest = File(inbox, EcgWearContract.inboxFileName("demo"))
        dest.writeBytes(app.healthtrack.data.protocol.DemoEcg.gzipBytes())
        ingestGzipFile(dest, "demo", EcgSource.IMPORT)
    }

    suspend fun ingestGzipFile(sourceFile: File, sessionId: String, source: EcgSource): EcgSession {
        return withContext(Dispatchers.IO) {
            val parsed = EcgCsvParser.parseFile(sourceFile, sessionId)
            persist(parsed, source, keepFile = sourceFile)
        }
    }

    suspend fun delete(sessionId: String) {
        val entity = dao.get(sessionId)
        dao.delete(sessionId)
        entity?.filePath?.let { path ->
            val file = File(path)
            if (file.exists()) file.delete()
        }
    }

    private suspend fun persist(
        parsed: ParsedEcgFile,
        source: EcgSource,
        keepFile: File? = null,
    ): EcgSession {
        val inbox = File(context.filesDir, EcgWearContract.INBOX_DIR).apply { mkdirs() }
        val dest = File(inbox, EcgWearContract.inboxFileName(parsed.sessionId))
        if (keepFile == null) {
            writeCanonical(dest, parsed)
        } else if (keepFile.canonicalPath != dest.canonicalPath) {
            keepFile.copyTo(dest, overwrite = true)
        }
        val entity = EcgSessionEntity.from(
            parsed = parsed,
            filePath = dest.absolutePath,
            source = source,
            now = System.currentTimeMillis(),
        )
        dao.upsert(entity)
        return entity.toDomain()
    }

    private fun writeCanonical(dest: File, parsed: ParsedEcgFile) {
        // Keep the original bytes when possible. For stream imports we already consumed
        // the stream, so persist a readable gzip of the parsed rows.
        dest.parentFile?.mkdirs()
        FileOutputStream(dest).use { out ->
            out.write(EcgCsvWriter.gzipBytes(EcgCsvWriter.encodeParsed(parsed)))
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return it.getString(idx)
            }
        }
        return uri.lastPathSegment
    }
}

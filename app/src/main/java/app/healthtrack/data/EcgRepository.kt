package app.healthtrack.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import app.healthtrack.analysis.EcgFounderEngine
import app.healthtrack.data.local.AppDatabase
import app.healthtrack.data.local.EcgSessionEntity
import app.healthtrack.data.protocol.EcgCsvParser
import app.healthtrack.data.protocol.EcgCsvWriter
import app.healthtrack.data.protocol.EcgFounderLabels
import app.healthtrack.data.protocol.EcgWearContract
import app.healthtrack.data.protocol.ParsedEcgFile
import app.healthtrack.domain.AnalysisStatus
import app.healthtrack.domain.EcgSample
import app.healthtrack.domain.EcgSession
import app.healthtrack.domain.EcgSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class EcgRepository(
    private val context: Context,
    db: AppDatabase,
    private val engine: EcgFounderEngine,
) {
    private val dao = db.ecgSessionDao()
    private val ingestMutex = Mutex()

    fun observeSessions(): Flow<List<EcgSession>> = dao.observeAll().map { rows ->
        rows.map { it.toDomain() }
    }

    fun observeLatest(): Flow<EcgSession?> = dao.observeLatest().map { it?.toDomain() }

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun get(sessionId: String): EcgSession? = dao.get(sessionId)?.toDomain()

    suspend fun loadSamples(session: EcgSession): List<EcgSample> = withContext(Dispatchers.IO) {
        val file = File(session.filePath)
        if (!file.exists()) return@withContext emptyList()
        try {
            EcgCsvParser.parseFile(file, session.sessionId).samples
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun importUri(uri: Uri): EcgSession = withContext(Dispatchers.IO) {
        ingestMutex.withLock {
            val name = queryDisplayName(uri) ?: "ecg_import.csv.gz"
            val baseId = EcgWearContract.sanitizeSessionId(name, "import")
            val sessionId = uniqueImportId(baseId)
            val parsed = context.contentResolver.openInputStream(uri)?.use { input ->
                EcgCsvParser.parseAutoStream(BufferedInputStream(input), sessionId)
            } ?: throw IllegalStateException("Unable to open that file")
            persistUnlocked(parsed, EcgSource.IMPORT)
        }
    }

    suspend fun ingestGzipFile(
        sourceFile: File,
        sessionId: String,
        source: EcgSource,
        expectedSha256: String? = null,
    ): EcgSession {
        return withContext(Dispatchers.IO) {
            ingestMutex.withLock {
                val safeSessionId = EcgWearContract.requireSessionId(sessionId)
                val incomingSha256 = EcgWearContract.sha256(sourceFile.readBytes())
                if (expectedSha256 != null && incomingSha256 != EcgWearContract.requireSha256(expectedSha256)) {
                    throw java.io.IOException("ECG payload SHA-256 mismatch")
                }
                var localSessionId = safeSessionId
                if (source == EcgSource.WEAR) {
                    localSessionId = wearLocalSessionId(safeSessionId)
                    val existing = dao.get(localSessionId)
                    if (existing?.source == EcgSource.WEAR.name && isValidStoredEcg(existing)) {
                        val storedSha256 = existing.payloadSha256
                            ?: EcgWearContract.sha256(File(existing.filePath).readBytes())
                        if (storedSha256 == incomingSha256) return@withLock existing.toDomain()
                        quarantineCollision(sourceFile, safeSessionId, incomingSha256)
                        throw java.io.IOException("ECG session id collision with different content")
                    }
                }
                val parsed = EcgCsvParser.parseFile(sourceFile, localSessionId)
                persistUnlocked(
                    parsed,
                    source,
                    keepFile = sourceFile,
                    payloadSha256 = incomingSha256,
                )
            }
        }
    }

    suspend fun ingestPendingInbox(): List<String> = withContext(Dispatchers.IO) {
        val inbox = File(context.filesDir, EcgWearContract.INBOX_DIR)
        val files = inbox.listFiles { f ->
            f.isFile && f.name.endsWith(EcgWearContract.FILE_SUFFIX)
        } ?: return@withContext emptyList()
        val ingested = ArrayList<String>(files.size)
        files.sortedBy { it.lastModified() }.forEach { file ->
            val sessionId = runCatching {
                EcgWearContract.sessionIdFromFileName(file.name)
            }.getOrNull() ?: return@forEach
            try {
                ingestGzipFile(file, sessionId, EcgSource.WEAR)
                ingested += sessionId
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val bad = File(file.parentFile, file.name + ".bad")
                if (bad.exists()) bad.delete()
                file.renameTo(bad)
            }
        }
        ingested
    }

    /** Deletes only explicit DEMO metadata and the exact legacy phone-demo signature. */
    suspend fun purgeDemoData(): Int = withContext(Dispatchers.IO) {
        ingestMutex.withLock {
            val inbox = File(context.filesDir, EcgWearContract.INBOX_DIR).apply { mkdirs() }
                .canonicalFile
            val removedIds = linkedSetOf<String>()
            dao.getDemoCleanupCandidates().filter(::isSafeDemoCleanupCandidate).forEach { entity ->
                dao.delete(entity.sessionId)
                deleteCanonicalFile(File(entity.filePath), inbox)
                removedIds += entity.sessionId
            }
            inbox.listFiles { file ->
                file.isFile && file.name.endsWith(EcgWearContract.FILE_SUFFIX)
            }.orEmpty().forEach { file ->
                if (EcgCsvParser.peekCaptureSourceToken(file) == "DEMO") {
                    val sessionId = runCatching {
                        EcgWearContract.sessionIdFromFileName(file.name)
                    }.getOrNull()
                    if (sessionId != null) {
                        dao.delete(sessionId)
                        removedIds += sessionId
                    }
                    deleteCanonicalFile(file, inbox)
                }
            }
            removedIds.size
        }
    }

    suspend fun reanalyze(sessionId: String): EcgSession? = withContext(Dispatchers.IO) {
        ingestMutex.withLock {
            val entity = dao.get(sessionId) ?: return@withLock null
            val file = File(entity.filePath)
            val analysed = try {
                if (!file.exists()) throw IllegalStateException("ECG waveform is missing")
                val parsed = EcgCsvParser.parseFile(file, sessionId)
                analyze(entity, parsed)
            } catch (err: Exception) {
                failedAnalysis(entity, err)
            }
            dao.upsert(analysed)
            analysed.toDomain()
        }
    }

    suspend fun delete(sessionId: String) = withContext(Dispatchers.IO) {
        ingestMutex.withLock {
            val entity = dao.get(sessionId)
            dao.delete(sessionId)
            entity?.filePath?.let { path ->
                val file = File(path)
                val inbox = File(context.filesDir, EcgWearContract.INBOX_DIR).canonicalFile
                if (file.canonicalFile.parentFile == inbox && file.exists()) file.delete()
            }
            Unit
        }
    }

    private suspend fun persistUnlocked(
        parsed: ParsedEcgFile,
        source: EcgSource,
        keepFile: File? = null,
        payloadSha256: String? = null,
    ): EcgSession {
        val inbox = File(context.filesDir, EcgWearContract.INBOX_DIR).apply { mkdirs() }
        val sessionId = EcgWearContract.requireSessionId(parsed.sessionId)
        val dest = File(inbox, EcgWearContract.inboxFileName(sessionId))
        if (keepFile == null) {
            writeCanonical(dest, parsed)
        } else if (keepFile.canonicalPath != dest.canonicalPath) {
            if (parsed.schemaVersion >= 2) {
                writeAtomic(dest, keepFile.readBytes())
            } else {
                writeCanonical(dest, parsed)
            }
        }
        val entity = EcgSessionEntity.from(
            parsed = parsed,
            filePath = dest.absolutePath,
            source = source,
            now = System.currentTimeMillis(),
            payloadSha256 = payloadSha256 ?: runCatching {
                EcgWearContract.sha256(dest.readBytes())
            }.getOrNull(),
        )
        dao.upsert(entity)
        val analysed = try {
            analyze(entity, parsed)
        } catch (err: Exception) {
            failedAnalysis(entity, err)
        }
        dao.upsert(analysed)
        return analysed.toDomain()
    }

    private fun analyze(entity: EcgSessionEntity, parsed: ParsedEcgFile): EcgSessionEntity {
        val result = engine.analyze(parsed)
        val decision = result.decision
        return entity.withAnalysis(
            status = result.status,
            naoLabel = decision?.label?.name,
            naoConfidence = decision?.confidence,
            findings = decision?.let { EcgFounderLabels.encodeFindings(it.topFindings) }.orEmpty(),
            note = result.note,
            qualityStatus = result.quality?.status?.name ?: entity.qualityStatus,
            cleanCoveragePct = result.quality?.cleanCoveragePct ?: entity.cleanCoveragePct,
            qualityFlagsJson = result.quality?.flagsJson() ?: entity.qualityFlagsJson,
            ecgHrMedian = result.ecgHrMedian,
            analysisBundleId = result.analysisBundleId,
        )
    }

    private fun failedAnalysis(entity: EcgSessionEntity, error: Exception): EcgSessionEntity =
        entity.withAnalysis(
            status = AnalysisStatus.FAILED,
            naoLabel = null,
            naoConfidence = null,
            findings = "",
            note = userFacingAnalysisError(error),
        )

    private fun writeCanonical(dest: File, parsed: ParsedEcgFile) {
        writeAtomic(
            dest,
            EcgCsvWriter.gzipBytes(EcgCsvWriter.encodeParsed(parsed)),
        )
    }

    private fun quarantineCollision(sourceFile: File, sessionId: String, sha256: String) {
        val quarantine = File(context.filesDir, "ecg_quarantine").apply { mkdirs() }
        val dest = File(quarantine, "${sessionId}-${sha256.take(16)}.collision")
        if (!dest.exists()) writeAtomic(dest, sourceFile.readBytes())
    }

    private fun writeAtomic(dest: File, bytes: ByteArray) {
        dest.parentFile?.mkdirs()
        val atomic = AtomicFile(dest)
        val output: FileOutputStream = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    private suspend fun uniqueImportId(baseId: String): String {
        val inbox = File(context.filesDir, EcgWearContract.INBOX_DIR)
        var candidate = EcgWearContract.requireSessionId(baseId)
        var attempt = 0
        while (dao.get(candidate) != null ||
            File(inbox, EcgWearContract.inboxFileName(candidate)).exists()
        ) {
            val suffix = "-${System.currentTimeMillis()}-${attempt++}"
            candidate = EcgWearContract.requireSessionId(baseId.take(80 - suffix.length) + suffix)
        }
        return candidate
    }

    private suspend fun wearLocalSessionId(remoteSessionId: String): String {
        val direct = dao.get(remoteSessionId)
        if (direct == null || direct.source == EcgSource.WEAR.name) return remoteSessionId

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(remoteSessionId.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        repeat(MAX_WEAR_COLLISION_ATTEMPTS) { attempt ->
            val suffix = "-wear-$digest" + if (attempt == 0) "" else "-$attempt"
            val candidate = EcgWearContract.requireSessionId(
                remoteSessionId.take(EcgWearContract.MAX_SESSION_ID_LENGTH - suffix.length) + suffix,
            )
            val existing = dao.get(candidate)
            if (existing == null || existing.source == EcgSource.WEAR.name) return candidate
        }
        throw IllegalStateException("Unable to allocate a local Wear session id")
    }

    private fun isValidStoredEcg(entity: EcgSessionEntity): Boolean {
        val file = File(entity.filePath)
        if (!file.isFile) return false
        return try {
            EcgCsvParser.parseFile(file, entity.sessionId)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteCanonicalFile(file: File, inbox: File) {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return
        if (canonical.parentFile == inbox && canonical.isFile) canonical.delete()
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

    companion object {
        private const val MAX_WEAR_COLLISION_ATTEMPTS = 32
    }
}

internal fun userFacingImportError(error: Throwable): String {
    val raw = error.message.orEmpty()
    return when {
        raw.contains("Empty file") -> "That file is empty."
        raw.contains("Missing #meta") -> "Not an ECG recording (missing #meta header)."
        raw.contains("No ECG samples") -> "That file has no ECG samples."
        raw.contains("exceeds", ignoreCase = true) || raw.contains("too large", ignoreCase = true) ->
            "That ECG file is too large."
        raw.contains("sample rate", ignoreCase = true) ||
            raw.contains("timestamp", ignoreCase = true) ||
            raw.contains("session id", ignoreCase = true) ||
            raw.contains("polarity", ignoreCase = true) ||
            raw.contains("Unsupported ECG", ignoreCase = true) ->
            "That file has invalid ECG metadata."
        raw.contains("Not in GZIP", ignoreCase = true) ||
            raw.contains("Invalid gzip", ignoreCase = true) ->
            "That file is not a valid gzip ECG."
        raw.contains("Unable to open") -> "Could not open that file."
        else -> "Import failed."
    }
}

internal fun isSafeDemoCleanupCandidate(entity: EcgSessionEntity): Boolean =
    entity.captureSource == "DEMO" ||
        (entity.source == EcgSource.IMPORT.name &&
            entity.watchInfo == "demo" &&
            entity.tsStartMs == 1_700_000_000_000L &&
            entity.srHz == 500 &&
            entity.nSamples == 4_000 &&
            (entity.sessionId == "demo" || entity.sessionId.startsWith("demo-")))

internal fun userFacingAnalysisError(error: Throwable): String {
    val raw = error.message.orEmpty()
    return when {
        raw.contains("ConvInteger") || raw.contains("ORT_NOT_IMPLEMENTED") ->
            "Rhythm model could not run on this device."
        else -> "Analysis failed."
    }
}

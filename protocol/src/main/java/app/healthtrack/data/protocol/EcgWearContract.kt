package app.healthtrack.data.protocol

/**
 * On-the-wire constants for Wear Data Layer ECG transfer.
 * Paths and keys match the watch writer / phone receiver contract.
 */
object EcgWearContract {
    const val MAX_SESSION_ID_LENGTH = 80

    const val SESSION_PREFIX = "/ecg/session/"
    const val CLEANUP_PREFIX = "/ecg/cleanup/"
    const val ACK_PREFIX = "/ecg/ack/"
    const val RESULT_PREFIX = "/ecg/result/"
    const val DELETE_PREFIX = "/ecg/delete/"
    const val DELETE_ALL = "/ecg/delete_all"
    const val DELETE_ALL_ACK = "/ecg/delete_all_ack"
    const val RESTORE_PREFIX = "/ecg/restore/"
    const val RESTORE_BATCH_PREFIX = "/ecg/restore-batch/"
    const val RPC_REQ_PREFIX = "/rpc/req/"

    const val KEY_SESSION_ID = "sessionId"
    const val KEY_TS = "ts"
    const val KEY_FORMAT = "format"
    const val KEY_NONCE = "nonce"
    const val KEY_ECG_FILE = "ecgFile"
    const val KEY_BYTE_COUNT = "byteCount"
    const val KEY_SHA256 = "sha256"

    const val FORMAT_CSV_GZ = "csv+gz"
    const val INBOX_DIR = "ecg_inbox"
    const val WATCH_DIR = "ecg"
    const val FILE_PREFIX = "ecg_"
    const val FILE_SUFFIX = ".csv.gz"

    const val DEFAULT_SR_HZ = 500
    const val MEASURE_DURATION_MS = 30_000L
    const val ASSET_TIMEOUT_SEC = 30L
    const val OFF_BODY_BLOCK_MS = 1_800L
    const val ECG_STALL_MS = 2_000L

    private val SESSION_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
    private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

    fun requireSessionId(raw: String): String {
        require(SESSION_ID_PATTERN.matches(raw)) {
            "Invalid ECG session id"
        }
        return raw
    }

    fun sanitizeSessionId(raw: String, fallback: String): String {
        val safeFallback = requireSessionId(fallback)
        val leaf = raw
            .trim()
            .replace('\\', '/')
            .substringAfterLast('/')
        val unwrapped = stripFileEnvelope(leaf)
        val sanitized = buildString(unwrapped.length) {
            unwrapped.forEach { char ->
                append(
                    when {
                        char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' -> char
                        char == '.' || char == '_' || char == '-' -> char
                        else -> '_'
                    },
                )
            }
        }
            .trim { it == '.' || it == '_' || it == '-' }
            .take(MAX_SESSION_ID_LENGTH)
        return sanitized.takeIf(SESSION_ID_PATTERN::matches) ?: safeFallback
    }

    fun sessionPath(sessionId: String): String = SESSION_PREFIX + requireSessionId(sessionId)

    fun cleanupPath(sessionId: String): String = CLEANUP_PREFIX + requireSessionId(sessionId)

    fun syncNowPath(nodeId: String): String = "${RPC_REQ_PREFIX}$nodeId/syncNow"

    fun inboxFileName(sessionId: String): String =
        FILE_PREFIX + requireSessionId(sessionId) + FILE_SUFFIX

    fun sessionIdFromFileName(name: String): String {
        require('/' !in name && '\\' !in name) { "Invalid ECG file name" }
        return requireSessionId(stripFileEnvelope(name))
    }

    private fun stripFileEnvelope(name: String): String {
        var id = name
        if (id.startsWith(FILE_PREFIX, ignoreCase = true)) {
            id = id.substring(FILE_PREFIX.length)
        }
        id = when {
            id.endsWith(FILE_SUFFIX, ignoreCase = true) -> id.dropLast(FILE_SUFFIX.length)
            id.endsWith(".gz", ignoreCase = true) -> id.dropLast(3)
            id.endsWith(".csv", ignoreCase = true) -> id.dropLast(4)
            else -> id
        }
        return id
    }

    fun signFactorFor(wrist: app.healthtrack.domain.Wrist): Int =
        if (wrist == app.healthtrack.domain.Wrist.RIGHT) -1 else 1

    fun requireSha256(raw: String): String {
        require(SHA256_PATTERN.matches(raw)) { "Invalid ECG SHA-256" }
        return raw
    }

    fun sha256(bytes: ByteArray): String = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}

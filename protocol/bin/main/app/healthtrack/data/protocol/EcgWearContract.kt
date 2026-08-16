package app.healthtrack.data.protocol

/**
 * On-the-wire constants for Wear Data Layer ECG transfer.
 * Paths and keys match the watch writer / phone receiver contract.
 */
object EcgWearContract {
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

    const val FORMAT_CSV_GZ = "csv+gz"
    const val INBOX_DIR = "ecg_inbox"
    const val WATCH_DIR = "ecg"
    const val FILE_PREFIX = "ecg_"
    const val FILE_SUFFIX = ".csv.gz"

    const val DEFAULT_SR_HZ = 500
    const val MEASURE_DURATION_MS = 30_000L
    const val ASSET_TIMEOUT_SEC = 30L
    const val LEAD_OFF_NO_CONTACT = 5
    const val HR_STATUS_OK = 1
    const val OFF_BODY_BLOCK_MS = 1_800L
    const val ECG_STALL_MS = 900L
    const val HR_LOST_ABORT_MS = 10_000L

    fun sessionPath(sessionId: String): String = SESSION_PREFIX + sessionId

    fun cleanupPath(sessionId: String): String = CLEANUP_PREFIX + sessionId

    fun syncNowPath(nodeId: String): String = "${RPC_REQ_PREFIX}$nodeId/syncNow"

    fun inboxFileName(sessionId: String): String = FILE_PREFIX + sessionId + FILE_SUFFIX

    fun sessionIdFromFileName(name: String): String {
        var id = name
        if (id.startsWith(FILE_PREFIX)) id = id.removePrefix(FILE_PREFIX)
        when {
            id.endsWith(FILE_SUFFIX) -> id = id.removeSuffix(FILE_SUFFIX)
            id.endsWith(".gz") -> id = id.removeSuffix(".gz")
            id.endsWith(".csv") -> id = id.removeSuffix(".csv")
        }
        return id
    }

    fun signFactorFor(wrist: app.healthtrack.domain.Wrist): Int =
        if (wrist == app.healthtrack.domain.Wrist.RIGHT) -1 else 1
}

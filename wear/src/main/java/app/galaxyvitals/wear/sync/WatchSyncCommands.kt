package app.galaxyvitals.wear.sync

import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.wear.store.WatchEcgStore

/** Applies phone Data Layer commands to the watch ECG store. */
class WatchSyncCommands(
    private val store: WatchEcgStore,
    private val onStoreChanged: () -> Unit = {},
    private val onSyncNow: () -> Unit = {},
) {
    fun handle(path: String): Boolean = when {
        path.startsWith(EcgWearContract.CLEANUP_PREFIX) -> {
            val acknowledged = path.removePrefix(EcgWearContract.CLEANUP_PREFIX)
            try {
                if (store.markSynced(acknowledged)) store.pruneAcknowledgedHistory()
                onStoreChanged()
            } catch (_: Exception) {
                // Ignore malformed or stale acknowledgements; pending data remains untouched.
            }
            true
        }
        path == EcgWearContract.DELETE_ALL -> {
            if (store.deleteAll() > 0) onStoreChanged()
            true
        }
        path.startsWith(EcgWearContract.DELETE_PREFIX) -> {
            val sessionId = path.removePrefix(EcgWearContract.DELETE_PREFIX)
            try {
                if (store.delete(sessionId)) onStoreChanged()
            } catch (_: Exception) {
                // Ignore malformed ids; local history stays as-is.
            }
            true
        }
        isSyncNow(path) -> {
            onSyncNow()
            true
        }
        else -> false
    }

    private fun isSyncNow(path: String): Boolean {
        if (!path.startsWith(EcgWearContract.RPC_REQ_PREFIX)) return false
        val parts = path.split('/')
        return parts.getOrNull(3) == "syncNow" || parts.getOrNull(4) == "syncNow"
    }
}

package app.galaxyvitals.wear.sync

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.wear.store.WatchEcgStore
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WatchWearListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        when {
            path.startsWith(EcgWearContract.CLEANUP_PREFIX) -> {
                val acknowledged = path.removePrefix(EcgWearContract.CLEANUP_PREFIX)
                try {
                    WatchEcgStore(this).apply {
                        if (markSynced(acknowledged)) pruneAcknowledgedHistory()
                    }
                } catch (_: Exception) {
                    // Ignore malformed or stale acknowledgements; pending data remains untouched.
                }
            }
            isSyncNow(path) -> {
                WorkManager.getInstance(this).enqueueUniqueWork(
                    SyncInboxWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<SyncInboxWorker>().build(),
                )
            }
        }
    }

    private fun isSyncNow(path: String): Boolean {
        if (!path.startsWith(EcgWearContract.RPC_REQ_PREFIX)) return false
        val parts = path.split('/')
        return parts.getOrNull(3) == "syncNow" || parts.getOrNull(4) == "syncNow"
    }
}

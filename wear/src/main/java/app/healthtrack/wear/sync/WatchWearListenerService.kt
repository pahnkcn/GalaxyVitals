package app.healthtrack.wear.sync

import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.healthtrack.data.protocol.EcgWearContract
import app.healthtrack.wear.store.WatchEcgStore
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WatchWearListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        when {
            path.startsWith(EcgWearContract.CLEANUP_PREFIX) -> {
                val sessionId = EcgWearContract.sessionIdFromFileName(
                    path.removePrefix(EcgWearContract.CLEANUP_PREFIX),
                )
                WatchEcgStore(this).delete(sessionId)
            }
            isSyncNow(path) -> {
                WorkManager.getInstance(this).enqueue(
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

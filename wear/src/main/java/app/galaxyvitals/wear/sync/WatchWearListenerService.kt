package app.galaxyvitals.wear.sync

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.galaxyvitals.wear.WearApplication
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WatchWearListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        val app = application as WearApplication
        WatchSyncCommands(
            store = app.container.store,
            onStoreChanged = app.container::notifyStoreChanged,
            onSyncNow = {
                WorkManager.getInstance(this).enqueueUniqueWork(
                    SyncInboxWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<SyncInboxWorker>().build(),
                )
            },
        ).handle(messageEvent.path)
    }
}

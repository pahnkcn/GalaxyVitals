package app.healthtrack.wear.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.healthtrack.wear.WearApplication

class SyncInboxWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as WearApplication
        return runCatching {
            app.container.dataLayer.putAllInbox(app.container.store)
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

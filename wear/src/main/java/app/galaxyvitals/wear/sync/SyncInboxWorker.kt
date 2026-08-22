package app.galaxyvitals.wear.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.galaxyvitals.wear.WearApplication
import kotlinx.coroutines.CancellationException

class SyncInboxWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as WearApplication
        return try {
            app.container.dataLayer.putAllInbox(app.container.store)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "wear-ecg-pending-sync"
    }
}

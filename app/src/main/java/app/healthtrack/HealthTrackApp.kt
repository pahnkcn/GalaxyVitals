package app.healthtrack

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HealthTrackApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch {
            val ids = container.ecgRepository.ingestPendingInbox()
            ids.forEach { sessionId ->
                runCatching { container.wearSyncClient.sendCleanup(sessionId) }
            }
        }
    }
}

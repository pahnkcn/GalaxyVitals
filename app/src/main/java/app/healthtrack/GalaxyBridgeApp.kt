package app.healthtrack

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GalaxyBridgeApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch {
            // Recover canonical files left between an atomic write and the Room commit.
            // Do not acknowledge the watch here: only the listener can first delete the
            // corresponding replicated DataItem and knows the original remote session ID.
            container.ecgRepository.ingestPendingInbox()
        }
    }
}

package app.healthtrack.wear

import android.app.Application
import app.healthtrack.wear.capture.EcgSessionRecorder
import app.healthtrack.wear.capture.MeasureForegroundLeaseManager
import app.healthtrack.wear.sensors.DemoEcgSensor
import app.healthtrack.wear.sensors.SamsungEcgSensor
import app.healthtrack.wear.store.WatchEcgStore
import app.healthtrack.wear.store.WearPreferences
import app.healthtrack.wear.sync.WatchDataLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class WearApplication : Application() {
    lateinit var container: WearContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = WearContainer(this)
    }
}

class WearContainer(app: Application) {
    val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val prefs = WearPreferences(app)
    val store = WatchEcgStore(app)
    val dataLayer = WatchDataLayer(app)
    val recorder = EcgSessionRecorder()
    val measureForegroundLeases = MeasureForegroundLeaseManager(app)
    val samsungSensor = SamsungEcgSensor(app)
    val demoSensor = DemoEcgSensor()
}

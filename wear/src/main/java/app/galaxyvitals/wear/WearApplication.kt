package app.galaxyvitals.wear

import android.app.Activity
import android.app.Application
import app.galaxyvitals.wear.capture.EcgSessionRecorder
import app.galaxyvitals.wear.capture.MeasureForegroundLeaseManager
import app.galaxyvitals.wear.sensors.EcgSensor
import app.galaxyvitals.wear.sensors.MeasurementSensorFactory
import app.galaxyvitals.wear.sensors.SamsungEcgSensor
import app.galaxyvitals.wear.store.WatchEcgStore
import app.galaxyvitals.wear.store.WearPreferences
import app.galaxyvitals.wear.sync.WatchDataLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
    val ecgSensor: EcgSensor = MeasurementSensorFactory.create(app)
    private val storeChangesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val storeChanges: SharedFlow<Unit> = storeChangesFlow.asSharedFlow()

    fun attachEcgSensor(activity: Activity) {
        (ecgSensor as? SamsungEcgSensor)?.attach(activity)
    }

    fun notifyStoreChanged() {
        storeChangesFlow.tryEmit(Unit)
    }
}

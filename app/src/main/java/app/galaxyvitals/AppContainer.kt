package app.galaxyvitals

import android.content.Context
import app.galaxyvitals.analysis.EcgFounderEngine
import app.galaxyvitals.data.EcgRepository
import app.galaxyvitals.data.local.AppDatabase
import app.galaxyvitals.data.wear.WearSyncClient

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: AppDatabase = AppDatabase.create(appContext)
    val ecgFounder: EcgFounderEngine = EcgFounderEngine(appContext)
    val ecgRepository: EcgRepository = EcgRepository(appContext, database, ecgFounder)
    val wearSyncClient: WearSyncClient = WearSyncClient(appContext)
}

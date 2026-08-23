package app.galaxyvitals

import android.content.Context
import app.galaxyvitals.analysis.EcgRhythmEngine
import app.galaxyvitals.data.EcgRepository
import app.galaxyvitals.data.local.AppDatabase
import app.galaxyvitals.data.wear.WearSyncClient

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: AppDatabase = AppDatabase.create(appContext)
    val ecgRhythmEngine: EcgRhythmEngine = EcgRhythmEngine(appContext)
    val ecgRepository: EcgRepository = EcgRepository(appContext, database, ecgRhythmEngine)
    val wearSyncClient: WearSyncClient = WearSyncClient(appContext)
}

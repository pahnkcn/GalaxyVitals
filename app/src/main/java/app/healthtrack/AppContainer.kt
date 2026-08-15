package app.healthtrack

import android.content.Context
import app.healthtrack.data.EcgRepository
import app.healthtrack.data.local.AppDatabase
import app.healthtrack.data.wear.WearSyncClient

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: AppDatabase = AppDatabase.create(appContext)
    val ecgRepository: EcgRepository = EcgRepository(appContext, database)
    val wearSyncClient: WearSyncClient = WearSyncClient(appContext)
}

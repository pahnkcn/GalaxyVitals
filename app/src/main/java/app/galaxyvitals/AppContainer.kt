package app.galaxyvitals

import android.content.Context
import app.galaxyvitals.analysis.EcgRhythmEngine
import app.galaxyvitals.data.EcgRepository
import app.galaxyvitals.data.local.AppDatabase
import app.galaxyvitals.data.wear.WearSyncClient
import app.galaxyvitals.export.EcgExporter
import app.galaxyvitals.ui.EcgScaleCalibration

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: AppDatabase = AppDatabase.create(appContext)
    val ecgRhythmEngine: EcgRhythmEngine = EcgRhythmEngine(appContext)
    val ecgRepository: EcgRepository = EcgRepository(appContext, database, ecgRhythmEngine)
    val wearSyncClient: WearSyncClient = WearSyncClient(appContext)
    val ecgExporter: EcgExporter = EcgExporter(appContext)
    val ecgScaleCalibration: EcgScaleCalibration = EcgScaleCalibration(appContext)
}

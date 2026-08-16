package app.healthtrack.wear.sync

import android.content.Context
import app.healthtrack.data.protocol.EcgWearContract
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

class WatchDataLayer(context: Context) {
    private val app = context.applicationContext
    private val dataClient = Wearable.getDataClient(app)
    private val nodeClient = Wearable.getNodeClient(app)

    suspend fun connectedPhoneNames(): List<String> {
        return runCatching { nodeClient.connectedNodes.await().map { it.displayName } }
            .getOrDefault(emptyList())
    }

    suspend fun putSession(sessionId: String, gzip: ByteArray) {
        val now = System.currentTimeMillis()
        val request = PutDataMapRequest.create(EcgWearContract.sessionPath(sessionId)).apply {
            dataMap.putString(EcgWearContract.KEY_SESSION_ID, sessionId)
            dataMap.putLong(EcgWearContract.KEY_TS, now)
            dataMap.putString(EcgWearContract.KEY_FORMAT, EcgWearContract.FORMAT_CSV_GZ)
            dataMap.putLong(EcgWearContract.KEY_NONCE, now)
            dataMap.putAsset(EcgWearContract.KEY_ECG_FILE, Asset.createFromBytes(gzip))
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request).await()
    }

    suspend fun putAllInbox(store: app.healthtrack.wear.store.WatchEcgStore): Int {
        val files = store.listGzipFiles()
        files.forEach { file ->
            putSession(EcgWearContract.sessionIdFromFileName(file.name), file.readBytes())
        }
        return files.size
    }
}

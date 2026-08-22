package app.healthtrack.wear.sync

import android.content.Context
import app.healthtrack.data.protocol.EcgWearContract
import app.healthtrack.wear.store.WatchEcgStore
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

class WatchDataLayer(context: Context) {
    private val app = context.applicationContext
    private val dataClient = Wearable.getDataClient(app)
    private val nodeClient = Wearable.getNodeClient(app)

    suspend fun connectedPhoneNames(): List<String> {
        return try {
            nodeClient.connectedNodes.await().map { it.displayName }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun putSession(sessionId: String, gzip: ByteArray) {
        // putDataItem() can succeed locally even when no companion is connected.
        // Do not report a recording as delivered until Wear OS exposes a target
        // phone; the pending watch file remains available for a later retry.
        requireConnectedPhone(nodeClient.connectedNodes.await())
        require(gzip.isNotEmpty()) { "ECG payload is empty" }
        val now = System.currentTimeMillis()
        val sha256 = EcgWearContract.sha256(gzip)
        val request = PutDataMapRequest.create(EcgWearContract.sessionPath(sessionId)).apply {
            dataMap.putString(EcgWearContract.KEY_SESSION_ID, sessionId)
            dataMap.putLong(EcgWearContract.KEY_TS, now)
            dataMap.putString(EcgWearContract.KEY_FORMAT, EcgWearContract.FORMAT_CSV_GZ)
            dataMap.putLong(EcgWearContract.KEY_NONCE, now)
            dataMap.putLong(EcgWearContract.KEY_BYTE_COUNT, gzip.size.toLong())
            dataMap.putString(EcgWearContract.KEY_SHA256, sha256)
            dataMap.putAsset(EcgWearContract.KEY_ECG_FILE, Asset.createFromBytes(gzip))
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request).await()
    }

    suspend fun putAllInbox(store: WatchEcgStore): Int {
        val files = store.listPendingGzipFiles()
        var uploaded = 0
        files.forEach { file ->
            val length = file.length()
            if (length !in 1L..WatchEcgStore.MAX_GZIP_BYTES.toLong()) {
                return@forEach
            }
            val gzip = file.readBytes()
            if (gzip.size > WatchEcgStore.MAX_GZIP_BYTES) return@forEach
            putSession(EcgWearContract.sessionIdFromFileName(file.name), gzip)
            uploaded += 1
        }
        return uploaded
    }
}

internal fun requireConnectedPhone(nodes: Collection<*>) {
    check(nodes.isNotEmpty()) {
        "No connected phone. Keep the GalaxyBridge phone app nearby and try Sync."
    }
}

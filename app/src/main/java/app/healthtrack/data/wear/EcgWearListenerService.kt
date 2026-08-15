package app.healthtrack.data.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.healthtrack.HealthTrackApp
import app.healthtrack.R
import app.healthtrack.data.protocol.EcgWearContract
import app.healthtrack.domain.EcgSource
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Phone-side receiver for `/ecg/session/{id}` DataItems.
 * Same contract as the original companion listener.
 */
class EcgWearListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val events = dataEvents.map { it }
        dataEvents.release()
        scope.launch {
            events.forEach { event ->
                if (event.type != DataEvent.TYPE_CHANGED) return@forEach
                val path = event.dataItem.uri.path ?: return@forEach
                if (!path.startsWith(EcgWearContract.SESSION_PREFIX)) return@forEach
                val sessionId = path.removePrefix(EcgWearContract.SESSION_PREFIX)
                val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                val asset = map.getAsset(EcgWearContract.KEY_ECG_FILE) ?: return@forEach
                ingest(sessionId, asset)
            }
        }
    }

    private suspend fun ingest(sessionId: String, asset: Asset) {
        val dataClient = Wearable.getDataClient(this)
        val fd = withTimeout(TimeUnit.SECONDS.toMillis(EcgWearContract.ASSET_TIMEOUT_SEC)) {
            dataClient.getFdForAsset(asset).await()
        }
        val inbox = File(filesDir, EcgWearContract.INBOX_DIR).apply { mkdirs() }
        val dest = File(inbox, EcgWearContract.inboxFileName(sessionId))
        fd.inputStream.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
        val app = application as HealthTrackApp
        app.container.ecgRepository.ingestGzipFile(dest, sessionId, EcgSource.WEAR)
        notifyReceived(sessionId)
        runCatching { app.container.wearSyncClient.sendCleanup(sessionId) }
    }

    private fun notifyReceived(sessionId: String) {
        val channelId = "ecg_inbox"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "ECG recordings", NotificationManager.IMPORTANCE_DEFAULT),
        )
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_ecg_notification)
            .setContentTitle(getString(R.string.ecg_received_title))
            .setContentText(getString(R.string.ecg_received_body, sessionId))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(sessionId.hashCode(), notification)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

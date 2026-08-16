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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        // DataEvent / DataItem are only valid while the buffer is open.
        val pending = ArrayList<Pair<String, Asset>>(dataEvents.count)
        try {
            for (event in dataEvents) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val path = event.dataItem.uri.path ?: continue
                if (!path.startsWith(EcgWearContract.SESSION_PREFIX)) continue
                val sessionId = path.removePrefix(EcgWearContract.SESSION_PREFIX)
                val asset = DataMapItem.fromDataItem(event.dataItem)
                    .dataMap
                    .getAsset(EcgWearContract.KEY_ECG_FILE)
                    ?: continue
                pending += sessionId to asset
            }
        } finally {
            dataEvents.release()
        }
        if (pending.isEmpty()) return
        val app = application as HealthTrackApp
        pending.forEach { (sessionId, asset) ->
            val dest = runCatching { writeAsset(sessionId, asset) }.getOrNull() ?: return@forEach
            app.appScope.launch {
                runCatching {
                    app.container.ecgRepository.ingestGzipFile(dest, sessionId, EcgSource.WEAR)
                    notifyReceived(sessionId)
                    app.container.wearSyncClient.sendCleanup(sessionId)
                }
            }
        }
    }

    private fun writeAsset(sessionId: String, asset: Asset): File {
        val dataClient = Wearable.getDataClient(this)
        val fd = runBlocking {
            withTimeout(TimeUnit.SECONDS.toMillis(EcgWearContract.ASSET_TIMEOUT_SEC)) {
                dataClient.getFdForAsset(asset).await()
            }
        }
        val inbox = File(filesDir, EcgWearContract.INBOX_DIR).apply { mkdirs() }
        val dest = File(inbox, EcgWearContract.inboxFileName(sessionId))
        fd.inputStream.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
        return dest
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
}

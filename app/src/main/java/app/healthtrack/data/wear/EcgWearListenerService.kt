package app.healthtrack.data.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.healthtrack.HealthTrackApp
import app.healthtrack.R
import app.healthtrack.data.protocol.EcgCsvParser
import app.healthtrack.data.protocol.EcgWearContract
import app.healthtrack.domain.EcgSource
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Phone-side receiver for `/ecg/session/{id}` DataItems.
 * Same contract as the original companion listener.
 */
class EcgWearListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        // DataEvent / DataItem are only valid while the buffer is open.
        val pending = ArrayList<Triple<String, Asset, android.net.Uri>>(dataEvents.count)
        try {
            for (event in dataEvents) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val path = event.dataItem.uri.path ?: continue
                if (!path.startsWith(EcgWearContract.SESSION_PREFIX)) continue
                val sessionId = runCatching {
                    EcgWearContract.requireSessionId(
                        path.removePrefix(EcgWearContract.SESSION_PREFIX),
                    )
                }.getOrNull() ?: continue
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                if (dataMap.getString(EcgWearContract.KEY_SESSION_ID) != sessionId) continue
                if (dataMap.getString(EcgWearContract.KEY_FORMAT) != EcgWearContract.FORMAT_CSV_GZ) continue
                val asset = dataMap.getAsset(EcgWearContract.KEY_ECG_FILE) ?: continue
                pending += Triple(sessionId, asset, event.dataItem.uri)
            }
        } finally {
            dataEvents.release()
        }
        if (pending.isEmpty()) return
        val app = application as HealthTrackApp
        pending.forEach { (sessionId, asset, dataItemUri) ->
            app.appScope.launch {
                RECEIVE_MUTEX.withLock {
                    try {
                        val incoming = writeAsset(sessionId, asset)
                        try {
                            app.container.ecgRepository.ingestGzipFile(
                                incoming,
                                sessionId,
                                EcgSource.WEAR,
                            )
                        } finally {
                            incoming.delete()
                        }
                        // Remove the replicated health-data item before telling the watch
                        // it may stop retrying the corresponding local recording.
                        Wearable.getDataClient(this@EcgWearListenerService)
                            .deleteDataItems(dataItemUri)
                            .await()
                        app.container.wearSyncClient.sendCleanup(sessionId)
                        notifyReceived(sessionId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // If deletion did not finish, the item remains retryable. If only the
                        // cleanup message failed, the watch stays pending and the next sync retries.
                    }
                }
            }
        }
    }

    private suspend fun writeAsset(sessionId: String, asset: Asset): File {
        val dataClient = Wearable.getDataClient(this)
        val fd = withTimeout(TimeUnit.SECONDS.toMillis(EcgWearContract.ASSET_TIMEOUT_SEC)) {
            dataClient.getFdForAsset(asset).await()
        }
        val incomingDir = File(filesDir, "ecg_incoming").apply { mkdirs() }
        incomingDir.listFiles { file -> file.isFile && file.name.endsWith(".part") }
            ?.forEach(File::delete)
        val dest = File.createTempFile("ecg-$sessionId-", ".csv.gz.part", incomingDir)
        val output = FileOutputStream(dest)
        try {
            fd.inputStream.use { input ->
                input.copyBoundedTo(output, EcgCsvParser.MAX_COMPRESSED_BYTES.toLong())
            }
            output.flush()
            output.fd.sync()
            output.close()
        } catch (error: Exception) {
            runCatching { output.close() }
            dest.delete()
            throw error
        }
        return dest
    }

    private fun InputStream.copyBoundedTo(output: FileOutputStream, maxBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) return
            copied += read
            if (copied > maxBytes) throw java.io.IOException("Compressed ECG exceeds size limit")
            output.write(buffer, 0, read)
        }
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
            .setContentText(getString(R.string.ecg_received_body))
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(sessionId.hashCode(), notification)
    }

    companion object {
        private val RECEIVE_MUTEX = Mutex()
    }
}

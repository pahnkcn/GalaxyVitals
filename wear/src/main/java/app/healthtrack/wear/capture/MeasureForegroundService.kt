package app.healthtrack.wear.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.healthtrack.wear.R

class MeasureForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val channelId = "ecg_measure"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, getString(R.string.measure_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_ecg_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.measure_notification))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(42, notification)
        }
        return START_STICKY
    }

    companion object {
        const val ACTION_STOP = "app.healthtrack.wear.STOP_MEASURE"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MeasureForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MeasureForegroundService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}

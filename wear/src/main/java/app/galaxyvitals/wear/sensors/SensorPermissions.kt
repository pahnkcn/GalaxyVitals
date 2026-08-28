package app.galaxyvitals.wear.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object SensorPermissions {
    const val READ_ADDITIONAL_HEALTH_DATA =
        "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
    const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"

    fun requiredForSdk(sdkInt: Int): List<String> = if (sdkInt >= 36) {
        listOf(READ_ADDITIONAL_HEALTH_DATA, READ_HEART_RATE)
    } else {
        listOf(Manifest.permission.BODY_SENSORS)
    }

    fun requiredForDevice(): List<String> = requiredForSdk(Build.VERSION.SDK_INT)

    fun missing(context: Context): List<String> = requiredForDevice().filter { permission ->
        ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    }

    fun hasAll(context: Context): Boolean = missing(context).isEmpty()
}

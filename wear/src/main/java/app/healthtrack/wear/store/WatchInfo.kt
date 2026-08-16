package app.healthtrack.wear.store

import android.content.Context
import android.os.Build
import app.healthtrack.wear.BuildConfig
import org.json.JSONObject

fun watchInfoJson(context: Context): String {
    return try {
        JSONObject()
            .put("model", Build.MODEL)
            .put("brand", Build.BRAND)
            .put("manufacturer", Build.MANUFACTURER)
            .put("os", "Wear OS ${Build.VERSION.RELEASE}")
            .put("appVersionName", BuildConfig.VERSION_NAME)
            .put("appVersionCode", BuildConfig.VERSION_CODE.toString())
            .put("package", context.packageName)
            .toString()
    } catch (_: Exception) {
        """{"model":"unknown"}"""
    }
}

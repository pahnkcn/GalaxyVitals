package app.galaxyvitals.wear.store

import android.content.Context
import android.os.Build
import app.galaxyvitals.wear.BuildConfig

fun watchInfoJson(context: Context): String = watchInfoJson(
    model = Build.MODEL,
    brand = Build.BRAND,
    manufacturer = Build.MANUFACTURER,
    os = "Wear OS ${Build.VERSION.RELEASE}",
    firmware = Build.DISPLAY,
    androidSdk = Build.VERSION.SDK_INT,
    appVersionName = BuildConfig.VERSION_NAME,
    appVersionCode = BuildConfig.VERSION_CODE.toString(),
    packageName = context.packageName,
)

internal fun watchInfoJson(
    model: String,
    brand: String,
    manufacturer: String,
    os: String,
    firmware: String,
    androidSdk: Int,
    appVersionName: String,
    appVersionCode: String,
    packageName: String,
): String {
    return try {
        buildString {
            append('{')
            jsonField("model", model)
            jsonField("brand", brand)
            jsonField("manufacturer", manufacturer)
            jsonField("os", os)
            jsonField("firmware", firmware)
            jsonField("androidSdk", androidSdk)
            jsonField("sensorSdk", BuildConfig.SAMSUNG_HEALTH_SENSOR_SDK_VERSION)
            jsonField("sensorAarSha256", BuildConfig.SAMSUNG_HEALTH_SENSOR_AAR_SHA256)
            jsonField("appVersionName", appVersionName)
            jsonField("appVersionCode", appVersionCode)
            jsonField("package", packageName, last = true)
            append('}')
        }
    } catch (_: Exception) {
        """{"model":"unknown"}"""
    }
}

private fun StringBuilder.jsonField(name: String, value: Int, last: Boolean = false) {
    append('"').append(name).append('"').append(':').append(value)
    if (!last) append(',')
}

private fun StringBuilder.jsonField(name: String, value: String, last: Boolean = false) {
    append('"').append(name).append('"').append(':').append(jsonQuote(value))
    if (!last) append(',')
}

private fun jsonQuote(value: String): String = buildString(value.length + 2) {
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
    append('"')
}

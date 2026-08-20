package app.healthtrack.analysis

import app.healthtrack.data.protocol.EcgFounderLabels
import android.content.Context
import org.json.JSONObject

data class NaoLinearHead(
    val coef: Array<FloatArray>,
    val intercept: FloatArray,
)

object NaoCalibrator {
    const val ASSET = "ecg/nao_calibrator.json"

    fun load(context: Context): NaoLinearHead? {
        val text = runCatching {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        return parse(text)
    }

    fun parse(json: String): NaoLinearHead? {
        return runCatching {
            val root = JSONObject(json)
            if (root.optString("type") != "logistic_nao") return null
            val coefJson = root.getJSONArray("coef")
            val interceptJson = root.getJSONArray("intercept")
            require(coefJson.length() == 3 && interceptJson.length() == 3)
            val coef = Array(3) { k ->
                val row = coefJson.getJSONArray(k)
                require(row.length() == EcgFounderLabels.ALL.size)
                FloatArray(row.length()) { i ->
                    row.getDouble(i).toFloat().also { require(it.isFinite()) }
                }
            }
            val intercept = FloatArray(3) { index ->
                interceptJson.getDouble(index).toFloat().also { require(it.isFinite()) }
            }
            NaoLinearHead(coef, intercept)
        }.getOrNull()
    }
}

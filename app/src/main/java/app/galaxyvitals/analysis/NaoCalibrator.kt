package app.galaxyvitals.analysis

import app.galaxyvitals.data.protocol.EcgFounderLabels
import android.content.Context
import org.json.JSONObject

data class NaoLinearHead(
    val coef: Array<FloatArray>,
    val intercept: FloatArray,
)

object NaoCalibrator {
    const val ASSET = "ecg/nao_calibrator.json"

    fun load(context: Context): NaoLinearHead {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        return parse(text)
    }

    fun parse(json: String): NaoLinearHead {
            val root = JSONObject(json)
            require(root.getString("type") == "logistic_nao")
            require(root.getInt("version") == 1)
            val labels = root.getJSONArray("labels")
            require(labels.length() == 3)
            require((0 until 3).map(labels::getString) == listOf("N", "A", "O"))
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
            return NaoLinearHead(coef, intercept)
    }
}

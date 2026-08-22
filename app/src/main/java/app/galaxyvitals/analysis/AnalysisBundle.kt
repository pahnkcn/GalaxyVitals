package app.galaxyvitals.analysis

import android.content.Context
import app.galaxyvitals.data.protocol.EcgFounderLabels
import app.galaxyvitals.data.protocol.NaoLabel
import app.galaxyvitals.data.protocol.EcgWearContract
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

data class BundleArtifact(val path: String, val sha256: String)

data class AnalysisBundle(
    val version: Int,
    val compatibilityId: String,
    val modelOutputCount: Int,
    val model: BundleArtifact,
    val labels: BundleArtifact,
    val filters: BundleArtifact,
    val calibrator: BundleArtifact,
    val thresholds: BundleArtifact,
    val classThresholds: Map<NaoLabel, Float>,
    val minimumWindowConsensus: Float,
) {
    fun verifyModelFile(file: File) {
        require(file.isFile && file.length() > 0L) { "ECGFounder model is missing" }
        require(FileInputStream(file).use(::sha256) == model.sha256) { "ECGFounder model hash mismatch" }
    }

    companion object {
        const val ASSET = "ecg/analysis_bundle.json"
        const val CURRENT_COMPATIBILITY_ID = "ecgfounder-1lead-nao-v1"

        fun load(context: Context): AnalysisBundle {
            val root = context.assets.open(ASSET).bufferedReader().use { JSONObject(it.readText()) }
            val artifacts = root.getJSONObject("artifacts")
            fun artifact(name: String): BundleArtifact {
                val item = artifacts.getJSONObject(name)
                return BundleArtifact(
                    path = item.getString("path"),
                    sha256 = EcgWearContract.requireSha256(item.getString("sha256")),
                )
            }
            val model = artifact("model")
            val labels = artifact("labels")
            val filters = artifact("filters")
            val calibrator = artifact("calibrator")
            val thresholds = artifact("thresholds")
            listOf(labels, filters, calibrator, thresholds).forEach { item ->
                val digest = context.assets.open(item.path).use(::sha256)
                require(digest == item.sha256) { "Analysis bundle hash mismatch: ${item.path}" }
            }
            val modelDigest = context.assets.open(model.path).use(::sha256)
            require(modelDigest == model.sha256) { "Analysis bundle hash mismatch: ${model.path}" }
            val labelsJson = context.assets.open(labels.path).bufferedReader().use { JSONObject(it.readText()) }
            val outputCount = root.getInt("model_output_count")
            require(outputCount == EcgFounderLabels.ALL.size)
            require(labelsJson.getInt("output_count") == outputCount)
            val thresholdJson = context.assets.open(thresholds.path).bufferedReader().use {
                JSONObject(it.readText())
            }
            val classJson = thresholdJson.getJSONObject("class_thresholds")
            val classThresholds = NaoLabel.entries.associateWith { label ->
                classJson.getDouble(label.name).toFloat().also { require(it in 0f..1f) }
            }
            val consensus = thresholdJson.getDouble("minimum_window_consensus").toFloat()
            require(consensus in 0f..1f)
            val compatibilityId = root.getString("compatibility_id")
            require(compatibilityId == CURRENT_COMPATIBILITY_ID)
            return AnalysisBundle(
                version = root.getInt("version"),
                compatibilityId = compatibilityId,
                modelOutputCount = outputCount,
                model = model,
                labels = labels,
                filters = filters,
                calibrator = calibrator,
                thresholds = thresholds,
                classThresholds = classThresholds,
                minimumWindowConsensus = consensus,
            )
        }

        private fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        }
    }
}

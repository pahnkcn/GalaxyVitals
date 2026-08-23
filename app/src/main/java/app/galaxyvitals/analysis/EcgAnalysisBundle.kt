package app.galaxyvitals.analysis

import android.content.Context
import app.galaxyvitals.data.protocol.EcgWearContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

data class EcgBundleArtifact(
    val path: String,
    val sha256: String,
)

data class EcgInputContract(
    val shape: List<Int>,
    val dtype: String,
    val sampleRateHz: Int,
    val durationSeconds: Int,
    val normalization: String,
    val polarity: String,
)

data class EcgOutputContract(
    val shape: List<Int>,
    val dtype: String,
    val semantics: String,
    val postprocess: String,
    val labels: List<String>,
)

data class EcgAnalysisBundle(
    val schema: String,
    val version: Int,
    val compatibilityId: String,
    val defaultVariant: String,
    val input: EcgInputContract,
    val output: EcgOutputContract,
    val model: EcgBundleArtifact,
    val filters: EcgBundleArtifact,
) {
    companion object {
        const val ASSET = "ecg/ecg_nao3_bundle.json"
        const val CURRENT_COMPATIBILITY_ID = "ecg-nao3-student-256hz-v1"

        private const val SCHEMA = "app.galaxyvitals.ecg.nao3.bundle"
        private const val VERSION = 1
        private const val DEFAULT_VARIANT = "fp32"
        private const val MODEL_ASSET = "ecg/ecg_nao3_student_fp32.tflite"
        private const val FILTERS_ASSET = "ecg/ecg_nao3_filters_256hz.json"
        private val INPUT_SHAPE = listOf(1, 7_680, 1)
        private val OUTPUT_SHAPE = listOf(1, 3)
        private val LABELS = listOf("N", "A", "O")

        fun load(context: Context): EcgAnalysisBundle {
            val root = context.assets.open(ASSET).bufferedReader().use { reader ->
                JSONObject(reader.readText())
            }
            root.requireKeys(
                "schema",
                "version",
                "compatibility_id",
                "default_variant",
                "input",
                "output",
                "artifacts",
            )
            val schema = root.getString("schema").also { require(it == SCHEMA) }
            val version = root.getInt("version").also { require(it == VERSION) }
            val compatibilityId = root.getString("compatibility_id").also {
                require(it == CURRENT_COMPATIBILITY_ID)
            }
            val defaultVariant = root.getString("default_variant").also {
                require(it == DEFAULT_VARIANT)
            }

            val inputJson = root.getJSONObject("input").apply {
                requireKeys(
                    "shape",
                    "dtype",
                    "sample_rate_hz",
                    "duration_seconds",
                    "normalization",
                    "polarity",
                )
            }
            val input = EcgInputContract(
                shape = inputJson.getJSONArray("shape").intList(),
                dtype = inputJson.getString("dtype"),
                sampleRateHz = inputJson.getInt("sample_rate_hz"),
                durationSeconds = inputJson.getInt("duration_seconds"),
                normalization = inputJson.getString("normalization"),
                polarity = inputJson.getString("polarity"),
            )
            require(input.shape == INPUT_SHAPE)
            require(input.dtype == "FLOAT32")
            require(input.sampleRateHz == 256)
            require(input.durationSeconds == 30)
            require(input.normalization == "zscore_per_record")
            require(input.polarity == "effective_once")

            val outputJson = root.getJSONObject("output").apply {
                requireKeys("shape", "dtype", "semantics", "postprocess", "labels")
            }
            val output = EcgOutputContract(
                shape = outputJson.getJSONArray("shape").intList(),
                dtype = outputJson.getString("dtype"),
                semantics = outputJson.getString("semantics"),
                postprocess = outputJson.getString("postprocess"),
                labels = outputJson.getJSONArray("labels").stringList(),
            )
            require(output.shape == OUTPUT_SHAPE)
            require(output.dtype == "FLOAT32")
            require(output.semantics == "logits")
            require(output.postprocess == "stable_softmax")
            require(output.labels == LABELS)

            val artifacts = root.getJSONObject("artifacts").apply {
                requireKeys("model", "filters")
            }
            val model = artifacts.artifact("model").also { require(it.path == MODEL_ASSET) }
            val filters = artifacts.artifact("filters").also { require(it.path == FILTERS_ASSET) }
            listOf(model, filters).forEach { artifact ->
                val actual = context.assets.open(artifact.path).use(::sha256)
                require(actual == artifact.sha256) {
                    "ECG analysis bundle hash mismatch: ${artifact.path}"
                }
            }

            return EcgAnalysisBundle(
                schema = schema,
                version = version,
                compatibilityId = compatibilityId,
                defaultVariant = defaultVariant,
                input = input,
                output = output,
                model = model,
                filters = filters,
            )
        }

        private fun JSONObject.artifact(name: String): EcgBundleArtifact {
            val artifact = getJSONObject(name).apply { requireKeys("path", "sha256") }
            return EcgBundleArtifact(
                path = artifact.getString("path"),
                sha256 = EcgWearContract.requireSha256(artifact.getString("sha256")),
            )
        }

        private fun JSONObject.requireKeys(vararg expected: String) {
            val actual = keys().asSequence().toSet()
            require(actual == expected.toSet()) {
                "Unexpected ECG bundle fields: $actual"
            }
        }

        private fun JSONArray.intList(): List<Int> =
            List(length()) { index -> getInt(index) }

        private fun JSONArray.stringList(): List<String> =
            List(length()) { index -> getString(index) }

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

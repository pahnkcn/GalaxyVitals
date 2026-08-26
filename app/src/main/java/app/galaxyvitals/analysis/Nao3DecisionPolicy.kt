package app.galaxyvitals.analysis

import app.galaxyvitals.data.protocol.NaoLabel
import org.json.JSONObject

data class Nao3ClassPolicy(
    val alwaysAbstain: Boolean,
    val minProbability: Float? = null,
    val demonstratedPrecision: Double? = null,
)

data class Nao3DecisionPolicy(
    val precisionTarget: Double,
    val minMargin: Float,
    val classes: Map<NaoLabel, Nao3ClassPolicy>,
    val splitStatus: String = CALIBRATION_ABSENT,
) {
    companion object {
        const val SCHEMA = "app.galaxyvitals.ecg.nao3.decision_policy"
        const val SPLIT_SCHEMA = "app.galaxyvitals.ecg.nao3.cinc2017.split"
        const val PRECISION_TARGET = 0.90
        const val CALIBRATION_ABSENT = "calibration_data_absent"

        fun alwaysAbstain(): Nao3DecisionPolicy = Nao3DecisionPolicy(
            precisionTarget = PRECISION_TARGET,
            minMargin = 1.0f,
            classes = NaoLabel.entries.associateWith { Nao3ClassPolicy(alwaysAbstain = true) },
            splitStatus = CALIBRATION_ABSENT,
        )

        fun parse(policyJson: String, splitJson: String): Nao3DecisionPolicy {
            val split = JSONObject(splitJson)
            require(split.getString("schema") == SPLIT_SCHEMA) {
                "Unexpected NAO3 split manifest schema"
            }
            val splitStatus = split.getString("status")
            val calibration = split.getJSONArray("calibration_records")
            val evaluation = split.getJSONArray("evaluation_records")

            val root = JSONObject(policyJson)
            require(root.getString("schema") == SCHEMA) {
                "Unexpected NAO3 decision policy schema"
            }
            require(root.getString("compatibility_id") == EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID) {
                "NAO3 decision policy compatibility id mismatch"
            }
            val precisionTarget = root.getDouble("precision_target")
            require(precisionTarget == PRECISION_TARGET) {
                "NAO3 decision policy precision target must be $PRECISION_TARGET"
            }
            val minMargin = root.getDouble("min_margin").toFloat()
            require(minMargin.isFinite() && minMargin >= 0f) {
                "NAO3 decision policy min_margin must be finite and non-negative"
            }
            val classesJson = root.getJSONObject("classes")
            val classes = NaoLabel.entries.associateWith { label ->
                val item = classesJson.getJSONObject(label.name)
                val alwaysAbstain = item.getBoolean("always_abstain")
                val precision = optionalFiniteDouble(item, "demonstrated_precision")
                val minProbability = optionalFiniteDouble(item, "min_probability")?.toFloat()
                if (!alwaysAbstain) {
                    require(precision != null && precision >= PRECISION_TARGET) {
                        "NAO3 class ${label.name} cannot be enabled without demonstrated precision >= $PRECISION_TARGET"
                    }
                    require(minProbability != null) {
                        "NAO3 class ${label.name} needs min_probability when it does not always abstain"
                    }
                }
                Nao3ClassPolicy(alwaysAbstain, minProbability, precision)
            }
            if (splitStatus == CALIBRATION_ABSENT || calibration.length() == 0) {
                require(evaluation.length() == 0) {
                    "Absent CinC 2017 calibration cannot claim evaluation records"
                }
                require(classes.values.all { it.alwaysAbstain }) {
                    "Absent CinC 2017 calibration cannot enable a rhythm class"
                }
            }
            return Nao3DecisionPolicy(
                precisionTarget = precisionTarget,
                minMargin = minMargin,
                classes = classes,
                splitStatus = splitStatus,
            )
        }

        private fun optionalFiniteDouble(item: JSONObject, key: String): Double? {
            if (!item.has(key) || item.isNull(key)) return null
            val value = item.getDouble(key)
            require(value.isFinite()) { "NAO3 policy field $key must be finite" }
            return value
        }
    }
}

class PolicyIntegrityException(cause: Throwable) : RuntimeException(cause)

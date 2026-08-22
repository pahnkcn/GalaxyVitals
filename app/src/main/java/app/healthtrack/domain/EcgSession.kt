package app.healthtrack.domain

enum class EcgSource {
    WEAR,
    IMPORT,
}

enum class AnalysisStatus {
    NONE,
    PENDING,
    OK,
    LOW_QUALITY,
    INDETERMINATE,
    FAILED,
}

data class EcgSession(
    val sessionId: String,
    val filePath: String,
    val tsStartMs: Long,
    val srHz: Int,
    val nSamples: Int,
    val durationSec: Double,
    val hrMedian: Double?,
    val hrMin: Int?,
    val hrMax: Int?,
    val hrCoveragePct: Double,
    val usablePct: Double,
    val wrist: Wrist,
    val signFactor: Int,
    val polarityNormalized: Boolean,
    val unit: String,
    val watchInfo: String,
    val source: EcgSource,
    val createdAtMs: Long,
    val analysisStatus: AnalysisStatus = AnalysisStatus.NONE,
    val naoLabel: String? = null,
    val naoConfidence: Float? = null,
    val findings: String = "",
    val analysisNote: String = "",
    val inputSchemaVersion: Int = 1,
    val timingTrust: String = "ASSUMED",
    val qualityStatus: String = "UNKNOWN",
    val cleanCoveragePct: Double = 0.0,
    val qualityFlagsJson: String = "[]",
    val ecgHrMedian: Double? = null,
    val analysisBundleId: String? = null,
    val payloadSha256: String? = null,
    val captureSource: String = "LEGACY",
)

data class BloodPressureReading(
    val id: String,
    val systolicMmhg: Int,
    val diastolicMmhg: Int,
    val pulseBpm: Int?,
    val recordedAtMs: Long,
)

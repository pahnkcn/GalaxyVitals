package app.galaxyvitals.domain

enum class Wrist {
    LEFT,
    RIGHT,
    UNKNOWN,
}

enum class CaptureSource {
    HARDWARE,
    IMPORT,
    LEGACY,
}

enum class TimingTrust {
    SENSOR,
    ASSUMED,
    UNVERIFIED,
    SEQUENCE_RECONSTRUCTED,
}

enum class SignalQualityStatus {
    UNKNOWN,
    GOOD,
    LOW_QUALITY,
    INVALID,
}

object EcgSampleFlags {
    const val NONE = 0
    const val SEQUENCE_GAP = 1 shl 0
    const val CONTACT_LOSS = 1 shl 1
    const val CLIPPED = 1 shl 2
    const val TIMESTAMP_GAP = 1 shl 3
    const val NONFINITE = 1 shl 4
}

data class EcgSample(
    val relMs: Long,
    val valueMv: Float,
    val hrBpm: Int?,
    val sampleIndex: Int = -1,
    val flags: Int = EcgSampleFlags.NONE,
    val sensorTimestampMsRaw: Long? = null,
    val batchSequence: Int? = null,
    val batchSampleOffset: Int? = null,
    val batchSize: Int? = null,
)

data class LiveBpmObservation(
    val atSampleIndex: Long,
    val observedCaptureElapsedMs: Long,
    val status: String,
    val displayedBpm: Double? = null,
    val rawBpm: Double? = null,
    val source: String? = null,
    val bSqi: Double? = null,
    val rrCount: Int? = null,
    val estimateAgeMs: Long = 0L,
    val reasonCode: String? = null,
)

data class LiveBpmSummary(
    val median: Double? = null,
    val min: Double? = null,
    val max: Double? = null,
    val reliableCoveragePct: Double = 0.0,
    val observationCount: Int = 0,
    val algorithmId: String? = null,
)

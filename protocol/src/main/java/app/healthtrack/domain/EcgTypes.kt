package app.healthtrack.domain

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
)

package app.healthtrack.domain

enum class Wrist {
    LEFT,
    RIGHT,
    UNKNOWN,
}

data class EcgSample(
    val relMs: Long,
    val valueMv: Float,
    val hrBpm: Int?,
)

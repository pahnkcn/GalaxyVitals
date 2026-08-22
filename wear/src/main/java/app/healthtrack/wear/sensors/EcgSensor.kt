package app.healthtrack.wear.sensors

data class EcgBatch(
    val samplesMv: FloatArray,
    val sensorTimestampsMs: LongArray,
    val sequence: Int,
    val leadOff: Int,
    val minThresholdMv: Float?,
    val maxThresholdMv: Float?,
    val sampleFlags: IntArray,
) {
    init {
        require(samplesMv.size == sensorTimestampsMs.size)
        require(samplesMv.size == sampleFlags.size)
        require(sequence in 0..255)
    }

    val contactValid: Boolean get() = leadOff == 0
}

enum class EcgSensorErrorCode {
    NOT_CONNECTED,
    START_FAILED,
    SDK_POLICY,
    TRACKER,
    INVALID_BATCH,
}

data class EcgSensorError(
    val code: EcgSensorErrorCode,
    val message: String,
)

data class SensorAvailability(
    val ready: Boolean,
    val reason: String? = null,
    val policyDenied: Boolean = false,
)

fun interface EcgSubscription : AutoCloseable {
    override fun close()
}

interface EcgSensor {
    fun connect(onResult: (SensorAvailability) -> Unit)
    fun startEcg(
        onError: (EcgSensorError) -> Unit = {},
        onBatch: (EcgBatch) -> Unit,
    ): EcgSubscription
    fun stop()
    fun disconnect()
}

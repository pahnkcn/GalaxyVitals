package app.galaxyvitals.wear.sensors

data class PpgGreenBatch(
    val values: IntArray,
    val ecgSampleOffsets: IntArray,
    val sensorTimestampsMs: LongArray,
    val nominalSampleRateHz: Int = 100,
) {
    init {
        require(values.size == ecgSampleOffsets.size)
        require(values.size == sensorTimestampsMs.size)
        require(nominalSampleRateHz > 0)
        for (i in 1 until ecgSampleOffsets.size) {
            require(ecgSampleOffsets[i - 1] < ecgSampleOffsets[i])
        }
    }
}

data class EcgBatch(
    val samplesMv: FloatArray,
    val sensorTimestampsMs: LongArray,
    val sequence: Int,
    val leadOff: Int,
    val minThresholdMv: Float?,
    val maxThresholdMv: Float?,
    val sampleFlags: IntArray,
    val ppgGreen: PpgGreenBatch? = null,
) {
    init {
        require(samplesMv.size == sensorTimestampsMs.size)
        require(samplesMv.size == sampleFlags.size)
        require(sequence in 0..255)
        ppgGreen?.ecgSampleOffsets?.forEach {
            require(it in samplesMv.indices)
        }
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

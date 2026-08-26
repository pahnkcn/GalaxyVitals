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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PpgGreenBatch) return false
        return nominalSampleRateHz == other.nominalSampleRateHz &&
            values.contentEquals(other.values) &&
            ecgSampleOffsets.contentEquals(other.ecgSampleOffsets) &&
            sensorTimestampsMs.contentEquals(other.sensorTimestampsMs)
    }

    override fun hashCode(): Int {
        var result = values.contentHashCode()
        result = 31 * result + ecgSampleOffsets.contentHashCode()
        result = 31 * result + sensorTimestampsMs.contentHashCode()
        result = 31 * result + nominalSampleRateHz
        return result
    }

    override fun toString(): String =
        "PpgGreenBatch(" +
            "values=${values.contentToString()}, " +
            "ecgSampleOffsets=${ecgSampleOffsets.contentToString()}, " +
            "sensorTimestampsMs=${sensorTimestampsMs.contentToString()}, " +
            "nominalSampleRateHz=$nominalSampleRateHz)"
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
    val sourceBatchSize: Int = samplesMv.size,
) {
    init {
        require(samplesMv.size == sensorTimestampsMs.size)
        require(samplesMv.size == sampleFlags.size)
        require(sequence in 0..255)
        require(sourceBatchSize >= samplesMv.size)
        ppgGreen?.ecgSampleOffsets?.forEach {
            require(it in samplesMv.indices)
        }
    }

    val contactValid: Boolean get() = leadOff == 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EcgBatch) return false
        return sequence == other.sequence &&
            leadOff == other.leadOff &&
            minThresholdMv == other.minThresholdMv &&
            maxThresholdMv == other.maxThresholdMv &&
            samplesMv.contentEquals(other.samplesMv) &&
            sensorTimestampsMs.contentEquals(other.sensorTimestampsMs) &&
            sampleFlags.contentEquals(other.sampleFlags) &&
            ppgGreen == other.ppgGreen &&
            sourceBatchSize == other.sourceBatchSize
    }

    override fun hashCode(): Int {
        var result = samplesMv.contentHashCode()
        result = 31 * result + sensorTimestampsMs.contentHashCode()
        result = 31 * result + sequence
        result = 31 * result + leadOff
        result = 31 * result + (minThresholdMv?.hashCode() ?: 0)
        result = 31 * result + (maxThresholdMv?.hashCode() ?: 0)
        result = 31 * result + sampleFlags.contentHashCode()
        result = 31 * result + (ppgGreen?.hashCode() ?: 0)
        result = 31 * result + sourceBatchSize
        return result
    }

    override fun toString(): String =
        "EcgBatch(" +
            "samplesMv=${samplesMv.contentToString()}, " +
            "sensorTimestampsMs=${sensorTimestampsMs.contentToString()}, " +
            "sequence=$sequence, " +
            "leadOff=$leadOff, " +
            "minThresholdMv=$minThresholdMv, " +
            "maxThresholdMv=$maxThresholdMv, " +
            "sampleFlags=${sampleFlags.contentToString()}, " +
            "ppgGreen=$ppgGreen)"
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
    val issue: SensorIssue? = null,
)

enum class SensorIssueCode {
    PACKAGE_NOT_INSTALLED,
    OLD_PLATFORM_VERSION,
    PERMISSION_ERROR,
    SDK_POLICY_ERROR,
    TRACKER_UNSUPPORTED,
    CONNECTION_FAILED,
}

enum class SensorRecovery {
    NONE,
    REQUEST_PERMISSION,
    RESOLVE_SERVICE,
    RETRY,
}

data class SensorIssue(
    val code: SensorIssueCode,
    val message: String,
    val recovery: SensorRecovery,
)

data class SensorAvailability(
    val ready: Boolean,
    val reason: String? = null,
    val issue: SensorIssue? = null,
)

fun interface EcgSubscription : AutoCloseable {
    override fun close()
}

interface EcgSensor {
    fun connect(onResult: (SensorAvailability) -> Unit)
    fun startEcg(
        maxDurationMs: Long,
        onError: (EcgSensorError) -> Unit = {},
        onBatch: (EcgBatch) -> Unit,
        onDeadline: () -> Unit = {},
    ): EcgSubscription
    fun resolvePending(activity: android.app.Activity): Boolean
    fun stop()
    fun disconnect()
}

package app.healthtrack.wear.sensors

enum class SensorKind { SAMSUNG, DEMO }

data class SensorAvailability(
    val kind: SensorKind,
    val ready: Boolean,
    val reason: String? = null,
    val policyDenied: Boolean = false,
)

interface EcgSensor {
    val kind: SensorKind
    fun connect(onResult: (SensorAvailability) -> Unit)
    fun startHr(onHr: (bpm: Int, status: Int) -> Unit)
    fun startEcg(onBatch: (mv: FloatArray, leadOff: Boolean) -> Unit)
    fun stop()
    fun disconnect()
}

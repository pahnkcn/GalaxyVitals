package app.galaxyvitals.wear.sensors

import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey

internal object SamsungHeartRateMapping {
    fun mapBatch(data: List<DataPoint>): HeartRateBatch {
        require(data.isNotEmpty()) { "Empty Samsung heart-rate batch" }
        return HeartRateBatch(data.map(::mapPoint))
    }

    private fun mapPoint(point: DataPoint): HeartRateSample {
        val ibiMs = readOptionalList(point, ValueKey.HeartRateSet.IBI_LIST)
        val ibiStatus = readOptionalList(point, ValueKey.HeartRateSet.IBI_STATUS_LIST)
        require(ibiMs.size == ibiStatus.size) {
            "Samsung heart-rate IBI values and statuses have different sizes"
        }
        require(ibiMs.size <= 4) { "Samsung heart-rate batch contains more than four IBI values" }
        return HeartRateSample(
            sensorTimestampMs = point.timestamp,
            bpm = point.getValue(ValueKey.HeartRateSet.HEART_RATE),
            status = point.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS),
            ibiMs = ibiMs,
            ibiStatus = ibiStatus,
        )
    }

    private fun readOptionalList(
        point: DataPoint,
        key: ValueKey<List<Int>?>,
    ): List<Int> = runCatching { point.getValue(key) }.getOrNull().orEmpty()
}

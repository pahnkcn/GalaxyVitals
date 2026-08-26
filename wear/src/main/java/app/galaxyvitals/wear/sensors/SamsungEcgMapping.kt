package app.galaxyvitals.wear.sensors

import app.galaxyvitals.domain.EcgSampleFlags
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey

internal object SamsungEcgMapping {
    const val MAX_ON_DEMAND_DURATION_MS = 30_000L

    fun mapBatch(data: List<DataPoint>): EcgBatch {
        if (data.size != 5 && data.size != 10) {
            throw IllegalArgumentException("Invalid Samsung ECG batch size: ${data.size}")
        }
        val first = data.first()
        val leadOff = first.getValue(ValueKey.EcgSet.LEAD_OFF)
        val sequence = first.getValue(ValueKey.EcgSet.SEQUENCE).toInt() and 0xff
        val minThreshold = first.getValue(ValueKey.EcgSet.MIN_THRESHOLD_MV)
        val maxThreshold = first.getValue(ValueKey.EcgSet.MAX_THRESHOLD_MV)
        require(minThreshold.isFinite() && maxThreshold.isFinite() && minThreshold < maxThreshold) {
            "Invalid Samsung ECG thresholds"
        }
        val samples = FloatArray(data.size)
        val timestamps = LongArray(data.size)
        val flags = IntArray(data.size)
        data.forEachIndexed { index, point ->
            val value = point.getValue(ValueKey.EcgSet.ECG_MV)
            require(value.isFinite()) { "Non-finite Samsung ECG sample" }
            samples[index] = value
            timestamps[index] = point.timestamp
            var sampleFlags = EcgSampleFlags.NONE
            if (leadOff != 0) sampleFlags = sampleFlags or EcgSampleFlags.CONTACT_LOSS
            if (value < minThreshold || value > maxThreshold) {
                sampleFlags = sampleFlags or EcgSampleFlags.CLIPPED
            }
            flags[index] = sampleFlags
        }
        val ppgGreen = SamsungPpgGreenDecoder.decode(
            batchSize = data.size,
            timestampAt = { data[it].timestamp },
            valueAt = { readPpgGreen(data[it]) },
        )
        return EcgBatch(
            samplesMv = samples,
            sensorTimestampsMs = timestamps,
            sequence = sequence,
            leadOff = leadOff,
            minThresholdMv = minThreshold,
            maxThresholdMv = maxThreshold,
            sampleFlags = flags,
            ppgGreen = ppgGreen,
        )
    }

    fun connectionIssue(exception: HealthTrackerException): SensorIssue {
        val message = exception.message ?: "Samsung Health connection failed."
        return when (exception.getErrorCode()) {
            HealthTrackerException.PACKAGE_NOT_INSTALLED -> SensorIssue(
                SensorIssueCode.PACKAGE_NOT_INSTALLED,
                message,
                SensorRecovery.RESOLVE_SERVICE,
            )
            HealthTrackerException.OLD_PLATFORM_VERSION -> SensorIssue(
                SensorIssueCode.OLD_PLATFORM_VERSION,
                message,
                SensorRecovery.RESOLVE_SERVICE,
            )
            else -> if (message.contains("permission", ignoreCase = true)) {
                SensorIssue(
                    SensorIssueCode.PERMISSION_ERROR,
                    message,
                    SensorRecovery.REQUEST_PERMISSION,
                )
            } else {
                SensorIssue(
                    SensorIssueCode.CONNECTION_FAILED,
                    message,
                    SensorRecovery.RETRY,
                )
            }
        }
    }

    fun trackerIssue(error: HealthTracker.TrackerError): SensorIssue = when (error) {
        HealthTracker.TrackerError.PERMISSION_ERROR -> SensorIssue(
            SensorIssueCode.PERMISSION_ERROR,
            "Samsung ECG tracker error: $error",
            SensorRecovery.REQUEST_PERMISSION,
        )
        HealthTracker.TrackerError.SDK_POLICY_ERROR -> SensorIssue(
            SensorIssueCode.SDK_POLICY_ERROR,
            "Samsung ECG tracker error: $error",
            SensorRecovery.NONE,
        )
    }

    fun missingOnDemandTracker(packageName: String): SensorIssue = SensorIssue(
        SensorIssueCode.TRACKER_UNSUPPORTED,
        "ECG_ON_DEMAND is not available for $packageName.",
        SensorRecovery.NONE,
    )

    fun bodySensorsDeniedIssue(): SensorIssue = SensorIssue(
        SensorIssueCode.PERMISSION_ERROR,
        "Body sensors permission is required to record ECG.",
        SensorRecovery.REQUEST_PERMISSION,
    )

    fun connectBlockedByBodySensors(granted: Boolean): SensorAvailability? {
        if (granted) return null
        val issue = bodySensorsDeniedIssue()
        return SensorAvailability(ready = false, reason = issue.message, issue = issue)
    }

    fun trackerError(error: HealthTracker.TrackerError): EcgSensorError {
        val issue = trackerIssue(error)
        val code = if (issue.code == SensorIssueCode.SDK_POLICY_ERROR) {
            EcgSensorErrorCode.SDK_POLICY
        } else {
            EcgSensorErrorCode.TRACKER
        }
        return EcgSensorError(code, issue.message, issue)
    }

    fun requireOnDemandDuration(maxDurationMs: Long) {
        if (maxDurationMs > MAX_ON_DEMAND_DURATION_MS) {
            throw IllegalArgumentException(
                "ECG_ON_DEMAND maxDurationMs must be <= $MAX_ON_DEMAND_DURATION_MS, was $maxDurationMs",
            )
        }
    }

    private fun readPpgGreen(point: DataPoint): Int? = try {
        point.getValue(ValueKey.EcgSet.PPG_GREEN)
    } catch (_: Exception) {
        null
    }
}

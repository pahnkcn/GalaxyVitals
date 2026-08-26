package app.galaxyvitals.wear.sensors

import com.google.common.truth.Truth.assertThat
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey
import org.junit.Test

class SamsungEcgSensorMappingTest {
    @Test
    fun mapBatchRejectsSizesOtherThan5And10() {
        for (size in listOf(0, 1, 6, 2, 7, 11)) {
            val error = runCatching { SamsungEcgMapping.mapBatch(points(size)) }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(error!!.message).contains("batch size")
        }
    }

    @Test
    fun mapBatchAcceptsSizes5And10() {
        val five = SamsungEcgMapping.mapBatch(points(5))
        assertThat(five.samplesMv).hasLength(5)
        assertThat(five.sequence).isEqualTo(1)
        val ten = SamsungEcgMapping.mapBatch(points(10))
        assertThat(ten.samplesMv).hasLength(10)
        assertThat(ten.sensorTimestampsMs).hasLength(10)
    }

    @Test
    fun packageNotInstalledMapsToResolveService() {
        val issue = SamsungEcgMapping.connectionIssue(
            HealthTrackerException(
                message = "Samsung Health Tracking Service is not installed.",
                errorCode = HealthTrackerException.PACKAGE_NOT_INSTALLED,
                hasResolution = true,
            ),
        )
        assertThat(issue.code).isEqualTo(SensorIssueCode.PACKAGE_NOT_INSTALLED)
        assertThat(issue.recovery).isEqualTo(SensorRecovery.RESOLVE_SERVICE)
    }

    @Test
    fun oldPlatformVersionMapsToResolveService() {
        val issue = SamsungEcgMapping.connectionIssue(
            HealthTrackerException(
                message = "Samsung Health platform is too old.",
                errorCode = HealthTrackerException.OLD_PLATFORM_VERSION,
                hasResolution = true,
            ),
        )
        assertThat(issue.code).isEqualTo(SensorIssueCode.OLD_PLATFORM_VERSION)
        assertThat(issue.recovery).isEqualTo(SensorRecovery.RESOLVE_SERVICE)
    }

    @Test
    fun permissionConnectionFailureMapsToRequestPermission() {
        val issue = SamsungEcgMapping.connectionIssue(
            HealthTrackerException(
                message = "Missing BODY_SENSORS permission.",
                errorCode = 99,
            ),
        )
        assertThat(issue.code).isEqualTo(SensorIssueCode.PERMISSION_ERROR)
        assertThat(issue.recovery).isEqualTo(SensorRecovery.REQUEST_PERMISSION)
    }

    @Test
    fun otherConnectionFailureMapsToRetry() {
        val issue = SamsungEcgMapping.connectionIssue(
            HealthTrackerException(
                message = "Binder died.",
                errorCode = 99,
            ),
        )
        assertThat(issue.code).isEqualTo(SensorIssueCode.CONNECTION_FAILED)
        assertThat(issue.recovery).isEqualTo(SensorRecovery.RETRY)
    }

    @Test
    fun trackerPermissionErrorMapsToRequestPermission() {
        val issue = SamsungEcgMapping.trackerIssue(HealthTracker.TrackerError.PERMISSION_ERROR)
        assertThat(issue.code).isEqualTo(SensorIssueCode.PERMISSION_ERROR)
        assertThat(issue.recovery).isEqualTo(SensorRecovery.REQUEST_PERMISSION)
    }

    @Test
    fun trackerSdkPolicyErrorIsTerminalNone() {
        val issue = SamsungEcgMapping.trackerIssue(HealthTracker.TrackerError.SDK_POLICY_ERROR)
        assertThat(issue.code).isEqualTo(SensorIssueCode.SDK_POLICY_ERROR)
        assertThat(issue.recovery).isEqualTo(SensorRecovery.NONE)
    }

    @Test
    fun bodySensorsDeniedBlocksConnectBeforeReady() {
        val denied = SamsungEcgMapping.connectBlockedByBodySensors(granted = false)
        assertThat(denied).isNotNull()
        assertThat(denied!!.ready).isFalse()
        assertThat(denied.issue!!.code).isEqualTo(SensorIssueCode.PERMISSION_ERROR)
        assertThat(denied.issue!!.recovery).isEqualTo(SensorRecovery.REQUEST_PERMISSION)
        assertThat(SamsungEcgMapping.connectBlockedByBodySensors(granted = true)).isNull()
    }

    @Test
    fun missingOnDemandCapabilityMapsToUnsupported() {
        val issue = SamsungEcgMapping.missingOnDemandTracker("app.galaxyvitals")
        assertThat(issue.code).isEqualTo(SensorIssueCode.TRACKER_UNSUPPORTED)
        assertThat(issue.recovery).isEqualTo(SensorRecovery.NONE)
        assertThat(issue.message).contains("ECG_ON_DEMAND")
    }

    private fun points(size: Int): List<DataPoint> = List(size) { index ->
        DataPoint(
            values = mapOf(
                ValueKey.EcgSet.ECG_MV to 0.1f,
                ValueKey.EcgSet.LEAD_OFF to 0,
                ValueKey.EcgSet.SEQUENCE to 1,
                ValueKey.EcgSet.MIN_THRESHOLD_MV to -5f,
                ValueKey.EcgSet.MAX_THRESHOLD_MV to 5f,
            ),
            timestamp = 1_000L + index * 2L,
        )
    }
}

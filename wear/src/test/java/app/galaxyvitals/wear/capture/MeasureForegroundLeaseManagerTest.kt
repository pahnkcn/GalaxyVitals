package app.galaxyvitals.wear.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MeasureForegroundLeaseManagerTest {
    @Test
    fun overlappingCaptureAndSaveCannotStopEachOther() {
        var starts = 0
        var stops = 0
        val manager = MeasureForegroundLeaseManager(
            startAction = { starts += 1 },
            stopAction = { stops += 1 },
        )

        val completedSave = manager.acquire()
        val nextCapture = manager.acquire()
        assertThat(starts).isEqualTo(1)
        assertThat(manager.activeLeaseCount).isEqualTo(2)

        completedSave.close()
        completedSave.close()
        assertThat(stops).isEqualTo(0)
        assertThat(manager.activeLeaseCount).isEqualTo(1)

        nextCapture.close()
        assertThat(stops).isEqualTo(1)
        assertThat(manager.activeLeaseCount).isEqualTo(0)

        manager.acquire().close()
        assertThat(starts).isEqualTo(2)
        assertThat(stops).isEqualTo(2)
    }
}

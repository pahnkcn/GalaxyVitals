package app.healthtrack.wear.ui

import app.healthtrack.data.protocol.EcgWearContract
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MeasureGuardsTest {
    @Test
    fun heartRateMustBeGoodAndRecent() {
        val now = 20_000L

        assertThat(isRecentHeartRate(hrOk = true, lastGoodAt = now - 1_000L, now = now)).isTrue()
        assertThat(isRecentHeartRate(hrOk = false, lastGoodAt = now - 1_000L, now = now)).isFalse()
        assertThat(
            isRecentHeartRate(
                hrOk = true,
                lastGoodAt = now - EcgWearContract.HR_LOST_ABORT_MS - 1L,
                now = now,
            ),
        ).isFalse()
        assertThat(isRecentHeartRate(hrOk = true, lastGoodAt = now + 1L, now = now)).isFalse()
        assertThat(isRecentHeartRate(hrOk = true, lastGoodAt = 0L, now = now)).isFalse()
    }
}

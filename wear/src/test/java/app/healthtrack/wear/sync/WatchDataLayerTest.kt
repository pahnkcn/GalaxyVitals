package app.healthtrack.wear.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WatchDataLayerTest {

    @Test
    fun uploadRequiresAConnectedPhone() {
        val error = runCatching { requireConnectedPhone(emptyList<Any>()) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error).hasMessageThat().contains("No connected phone")
    }

    @Test
    fun uploadCanProceedWhenWearOsReportsAConnectedNode() {
        val error = runCatching { requireConnectedPhone(listOf(Any())) }.exceptionOrNull()

        assertThat(error).isNull()
    }
}

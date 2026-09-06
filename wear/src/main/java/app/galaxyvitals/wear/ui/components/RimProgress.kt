package app.galaxyvitals.wear.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CircularProgressIndicatorDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults

/**
 * The rim is the quantity.
 *
 * A round screen already has a ring; anything that fills or drains belongs on
 * it, so the middle of the screen stays free for the number and the trace. One
 * revolution is one whole.
 *
 * The 120°→60° sweep is structural rather than decorative: the gap it leaves is
 * centred on six o'clock, which is exactly where the bottom arc and the
 * EdgeButton sit, so the rim can never run into either of them.
 *
 * The rim is always drawn in the app's one neutral, including on success. The
 * watch does not run the rhythm model — the phone does — so it has no verdict
 * to report, and a green ring here would spend the one colour that means
 * "regular rhythm" on something that is merely finished.
 */
object RimProgress {

    /** A known fraction: recording elapsed, or a countdown draining. */
    @Composable
    fun Determinate(progress: () -> Float) {
        Rim { CircularProgressIndicator(progress = progress, startAngle = START, endAngle = END, colors = rimColors()) }
    }

    /** Working, duration unknown. Connecting, preparing, saving. */
    @Composable
    fun Working() {
        Rim { CircularProgressIndicator(colors = rimColors()) }
    }

    /** Closed ring: the recording is complete. */
    @Composable
    fun Complete() {
        Rim { CircularProgressIndicator(progress = { 1f }, startAngle = START, endAngle = END, colors = rimColors()) }
    }

    /** The track alone, for the states where nothing is counting. */
    @Composable
    fun Idle() {
        Rim { CircularProgressIndicator(progress = { 0f }, startAngle = START, endAngle = END, colors = rimColors()) }
    }

    /**
     * Takes no modifier on purpose. The rim is positioned by the screen's own
     * bezel, so it must never receive a scaffold's content padding as well — a
     * second inset makes the ring float visibly inside the edge of the glass.
     */
    @Composable
    private fun Rim(indicator: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().padding(CircularProgressIndicatorDefaults.FullScreenPadding)) {
            indicator()
        }
    }

    @Composable
    private fun rimColors() = ProgressIndicatorDefaults.colors(
        indicatorColor = MaterialTheme.colorScheme.onBackground,
        trackColor = MaterialTheme.colorScheme.outline,
    )

    private const val START = 120f
    private const val END = 60f
}

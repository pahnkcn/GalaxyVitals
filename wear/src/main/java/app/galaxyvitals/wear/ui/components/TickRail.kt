package app.galaxyvitals.wear.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import app.galaxyvitals.wear.ui.theme.rememberReduceMotion
import kotlin.math.floor

/**
 * The rail is the clock.
 *
 * A round screen has a rim to fill; a rectangle does not, so quantity is
 * carried by counting instead. Thirty ticks standing on a hairline baseline is
 * a chart's own time axis, borrowed from the paper the trace is printed on: one
 * tick is one second, and the number of ticks tells you the scale without a
 * label. The arming countdown uses three wide ticks and drains; the recording
 * uses thirty narrow ones and fills. A fuse, then a jar.
 *
 * The rail is always drawn in the app's one neutral. The watch does not run the
 * rhythm model — the phone does — so it has no verdict to report, and a green
 * rail on success would spend the colour that means "regular rhythm" on a file
 * that merely finished writing.
 */
object TickRail {

    /** A known count: ticks lit from the left. */
    @Composable
    fun Determinate(
        total: Int,
        lit: Int,
        modifier: Modifier = Modifier,
        contentDescription: String? = null,
    ) {
        Rail(total, modifier, contentDescription) { index -> index < lit }
    }

    /** Every tick lit: the recording is complete. */
    @Composable
    fun Complete(
        modifier: Modifier = Modifier,
        total: Int = RECORD_TICKS,
        contentDescription: String? = null,
    ) {
        Rail(total, modifier, contentDescription) { true }
    }

    /**
     * Working, duration unknown: a short block sliding along the same rail.
     *
     * Deliberately the same object as the determinate rail rather than a
     * separate spinner, so "busy" and "counting" are never two different shapes
     * in the same corner of the screen.
     */
    @Composable
    fun Working(
        modifier: Modifier = Modifier,
        total: Int = RECORD_TICKS,
        contentDescription: String? = null,
    ) {
        val reduce = rememberReduceMotion()
        if (reduce) {
            // Standing still, a sweep says nothing. A third of the rail lit is
            // the honest static reading of "something is happening".
            Rail(total, modifier, contentDescription) { it < total / 3 }
            return
        }
        val head by rememberInfiniteTransition(label = "rail").animateFloat(
            initialValue = -SWEEP_TICKS.toFloat(),
            targetValue = total.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(SWEEP_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "sweep",
        )
        Rail(total, modifier, contentDescription) { index ->
            val start = floor(head).toInt()
            index >= start && index < start + SWEEP_TICKS
        }
    }

    /**
     * One canvas rather than a row of boxes.
     *
     * Thirty composables for thirty ticks is thirty layout nodes redrawn once a
     * second on a watch; the whole rail is one draw call this way, and the tick
     * pitch stays exact instead of accumulating rounding across flex children.
     */
    @Composable
    private fun Rail(
        total: Int,
        modifier: Modifier,
        contentDescription: String?,
        isLit: (Int) -> Boolean,
    ) {
        val litColor = MaterialTheme.colorScheme.onSurface
        val trackColor = MaterialTheme.colorScheme.outline
        // Fewer ticks means each is wider, so a three-tick countdown reads as
        // three bars and a thirty-tick capture reads as a scale.
        val tickHeight = if (total <= WIDE_TICK_LIMIT) WIDE_TICK_HEIGHT else TICK_HEIGHT
        Canvas(
            modifier
                .fillMaxWidth()
                .height(tickHeight + BASELINE_GAP)
                .then(
                    if (contentDescription == null) Modifier
                    else Modifier.semantics { this.contentDescription = contentDescription },
                ),
        ) {
            val gap = TICK_GAP.toPx()
            val pitch = (size.width + gap) / total
            val width = (pitch - gap).coerceAtLeast(1f)
            val height = tickHeight.toPx()
            for (index in 0 until total) {
                drawRect(
                    color = if (isLit(index)) litColor else trackColor,
                    topLeft = Offset(index * pitch, 0f),
                    size = Size(width, height),
                )
            }
            drawRect(
                color = trackColor,
                topLeft = Offset(0f, size.height - BASELINE.toPx()),
                size = Size(size.width, BASELINE.toPx()),
            )
        }
    }

    /** How many ticks a full capture is worth. The contract's own length. */
    const val RECORD_TICKS = 30

    /** And the arming window before it. */
    const val ARM_TICKS = 3

    /** Ticks lit for a whole number of elapsed seconds. */
    fun elapsedTicks(total: Int, remainingSec: Int): Int =
        (total - remainingSec).coerceIn(0, total)

    /** Ticks still standing on a countdown that drains. */
    fun remainingTicks(total: Int, remainingSec: Int): Int = remainingSec.coerceIn(0, total)

    private const val SWEEP_TICKS = 6
    private const val SWEEP_MS = 1_400
    private const val WIDE_TICK_LIMIT = 6
    private val TICK_HEIGHT = 7.dp
    private val WIDE_TICK_HEIGHT = 11.dp
    private val TICK_GAP = 1.5.dp
    private val BASELINE = 1.dp
    private val BASELINE_GAP = 4.dp
}


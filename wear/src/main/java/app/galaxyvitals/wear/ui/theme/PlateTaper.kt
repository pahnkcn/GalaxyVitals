package app.galaxyvitals.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A list row is as wide as its own lowest corner allows.
 *
 * The plate tiers in [Plate] are solved for a plate that sits still. A row in a
 * scrolling list does not: it starts in the middle of the face where the chord
 * is widest and travels to the top or the bottom where it is not. Sizing every
 * row for the worst case would waste the middle of the screen; sizing them for
 * the middle clips their corners at the edge.
 *
 * So the row measures where it actually is and narrows to fit, which is the
 * same rule the static tiers come from applied continuously. The list ends in a
 * taper instead of a clip, and the taper is information: it is the glass.
 *
 * Only the horizontal scale moves. Scaling both axes would shrink the text as
 * well, and a row you cannot read is not a better outcome than a row with tight
 * corners.
 */
@Composable
fun Modifier.plateTaper(): Modifier {
    if (!isRoundScreen()) return this
    val density = LocalDensity.current
    val face = faceDp()
    val centreY = with(density) { (face / 2f).dp.toPx() }
    val safeRadiusPx = with(density) { safeRadius().toPx() }
    val scale = remember { mutableFloatStateOf(1f) }
    return this
        .onPlaced { coordinates ->
            val halfWidth = coordinates.size.width / 2f
            if (halfWidth <= 0f) return@onPlaced
            val top = coordinates.positionInRoot().y
            val bottom = top + coordinates.size.height
            // The corner that is furthest from the centre decides, which is the
            // whole point: a tall row near the edge is governed by its far edge,
            // not by where its middle happens to sit.
            val dy = max(abs(top - centreY), abs(bottom - centreY))
            val availableSquared = safeRadiusPx * safeRadiusPx - dy * dy
            scale.floatValue = if (availableSquared <= 0f) {
                MIN_TAPER
            } else {
                (sqrt(availableSquared) / halfWidth).coerceIn(MIN_TAPER, 1f)
            }
        }
        // Read inside the layer block rather than at composition, so a scroll
        // repaints the row without recomposing it.
        .graphicsLayer { scaleX = scale.floatValue }
}

/** Past this the row is mostly off screen anyway, and shrinking it further only blurs it. */
private const val MIN_TAPER = 0.72f

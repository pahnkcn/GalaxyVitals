package app.galaxyvitals.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The screen's own shape and size.
 *
 * Wear Compose has `isRoundDevice()` and `screenWidthFraction()` but both are
 * internal to the library, so the same two facts are read here from the
 * platform configuration, which is where the library reads them from too.
 *
 * Fractions matter on a round screen because a chord is only as wide as the
 * diameter at the very middle. Sizing a rectangle at a fraction of the screen
 * is how it is kept clear of the bezel at the height it actually sits at.
 */
@Composable
fun isRoundScreen(): Boolean = LocalConfiguration.current.isScreenRound

@Composable
fun screenWidthFraction(fraction: Float): Dp = (LocalConfiguration.current.screenWidthDp * fraction).dp

@Composable
fun screenHeightFraction(fraction: Float): Dp = (LocalConfiguration.current.screenHeightDp * fraction).dp

/**
 * The side margin a scrolling list needs to stay off the bezel.
 *
 * A list item is a rectangle and the screen is a disc, so the item's corners
 * reach the glass long before its middle does. The library's own content
 * padding is sized for the widest chord; anything that scrolls past the top or
 * the bottom of the circle needs more than that, which is why the inset is a
 * fraction of the screen rather than a fixed number of dp.
 */
@Composable
fun listSideMargin(): Dp = if (isRoundScreen()) screenWidthFraction(LIST_SIDE_FRACTION) else 8.dp

private const val LIST_SIDE_FRACTION = 0.05f

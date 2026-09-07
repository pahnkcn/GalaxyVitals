package app.galaxyvitals.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The plate grid: how wide a rectangle is allowed to be on round glass.
 *
 * A rectangle is only as wide as the chord at its furthest corner from the
 * centre, not the chord at its middle. So for a plate whose outermost edge sits
 * `dy` from the centre,
 *
 *     halfWidth(dy) = sqrt(safeRadius² - dy²)
 *
 * Solving that once for the three heights the app actually uses gives three
 * tiers, and every plate in the app picks one. The stepped silhouette that
 * falls out — wide through the middle, shouldering in at the top and bottom —
 * is the look, not a compromise.
 *
 * Everything here is a *fraction of the face*, never a fixed dp — which is the
 * whole reason this survived contact with the hardware. A 480 × 480 panel looks
 * like a 240 dp face if you assume xhdpi, and the SM-L350 does not: it reports
 * 340 dpi, a density of 2.125, so its face is 225 dp. The numbers below are
 * measured on the device for the base and derived at the same density for the
 * other two.
 *
 * | device                 | pixels    | face   | Core | Action | Band |
 * |------------------------|-----------|--------|------|--------|------|
 * | Watch 9 40 mm          | 438 × 438 | 206 dp | 146  | 119    | 103  |
 * | Watch 9 44 mm (base)   | 480 × 480 | 225 dp | 160  | 130    | 113  |
 * | Watch Ultra 2          | 498 × 498 | 234 dp | 166  | 136    | 117  |
 *
 * Only the 225 dp row is confirmed on real hardware. Read the face at runtime
 * rather than trusting this table — that is what [faceDp] is for.
 *
 * Type and touch targets deliberately do *not* scale with the face: 48 dp is
 * 48 dp on every wrist. Only the widths and the vertical inset move, so the
 * small face simply runs tighter gaps rather than smaller text.
 */
enum class Plate(internal val widthFraction: Float) {
    /** Hero readouts, the trace, the tick rail, list rows, the segmented control. */
    Core(0.71f),

    /** The action bar, the last rule before the edge, a trailing list row. */
    Action(0.58f),

    /** The status line, and captions that ride nearest the glass. */
    Band(0.50f),
}

/**
 * The chord maths, with no Compose in it.
 *
 * Kept separate from the composables below so the claim this whole layout rests
 * on — that the same three fractions clear the glass on every face the app
 * ships to — is something a unit test can check rather than something a comment
 * asserts. See `PlateGeometryTest`.
 */
internal object PlateGeometry {

    /**
     * How much of the radius a plate may use.
     *
     * 0.473 rather than 0.5 holds roughly 6 dp of the 240 dp face back for the
     * bezel, which is what keeps a square corner off the curve of the glass
     * rather than merely inside it.
     */
    const val SAFE_RADIUS_FRACTION = 0.473f

    fun safeRadius(face: Float): Float = face * SAFE_RADIUS_FRACTION

    fun width(face: Float, plate: Plate): Float = face * plate.widthFraction

    /** How far from the centre this tier's corner still clears the glass. */
    fun reach(face: Float, plate: Plate): Float {
        val safeRadius = safeRadius(face)
        val halfWidth = width(face, plate) / 2f
        return sqrt((safeRadius * safeRadius - halfWidth * halfWidth).coerceAtLeast(0f))
    }

    /** The top and bottom inset, set by the tier that rides closest to the glass. */
    fun verticalInset(face: Float): Float = face / 2f - reach(face, Plate.Band)

    /** Everything between those two insets. */
    fun usableHeight(face: Float): Float = 2f * reach(face, Plate.Band)
}

/** A square screen has no chord to respect, so plates take almost the whole width. */
private const val SQUARE_WIDTH_FRACTION = 0.92f

/** And its only inset is the ordinary one that keeps text off the edge. */
private val SQUARE_INSET = 8.dp

@Composable
@ReadOnlyComposable
fun isRoundScreen(): Boolean = LocalConfiguration.current.isScreenRound

/**
 * The face, as one number.
 *
 * Round Wear screens are square-bounded and equal in both axes; taking the
 * smaller side is what makes this correct on the rectangular emulators too.
 */
@Composable
@ReadOnlyComposable
fun faceDp(): Float {
    val configuration = LocalConfiguration.current
    return min(configuration.screenWidthDp, configuration.screenHeightDp).toFloat()
}

/** The width this tier is allowed on this face. */
@Composable
@ReadOnlyComposable
fun plateWidth(plate: Plate): Dp =
    if (isRoundScreen()) PlateGeometry.width(faceDp(), plate).dp
    else (faceDp() * SQUARE_WIDTH_FRACTION).dp

/**
 * How far from the centre this tier still clears the glass.
 *
 * Exposed because it is what sets the scaffold's vertical padding: the content
 * column may only be as tall as the narrowest plate in it can reach.
 */
@Composable
@ReadOnlyComposable
fun plateReach(plate: Plate): Dp =
    if (isRoundScreen()) PlateGeometry.reach(faceDp(), plate).dp
    else (faceDp() / 2f).dp - SQUARE_INSET

/**
 * The top and bottom inset for a screen's content.
 *
 * Measured from the Band tier, because the status line is the thing that rides
 * closest to the glass and therefore decides where the column may start.
 */
@Composable
@ReadOnlyComposable
fun safeVerticalInset(): Dp = (faceDp() / 2f).dp - plateReach(Plate.Band)

/**
 * The side margin a scrolling list needs.
 *
 * Half the difference between the face and the Core plate, so a list row lines
 * up with every other Core-width plate in the app rather than with the bezel.
 */
@Composable
@ReadOnlyComposable
fun listSideMargin(): Dp = ((faceDp().dp - plateWidth(Plate.Core)) / 2f)

/** The safe radius in dp, for anything that has to do the chord maths itself. */
@Composable
@ReadOnlyComposable
fun safeRadius(): Dp =
    if (isRoundScreen()) PlateGeometry.safeRadius(faceDp()).dp
    else (faceDp() / 2f).dp - SQUARE_INSET

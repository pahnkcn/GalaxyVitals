package app.galaxyvitals.wear.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.foundation.semantics
import androidx.wear.compose.material3.CurvedTextDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.curvedText

/**
 * Labels that ride the bezel.
 *
 * A round screen has two kinds of room: the disc in the middle, and the band
 * around its edge. Short names — a status, a countdown, the clock — belong on
 * the band, because putting them in the middle spends the widest chord of the
 * screen on words that never change. Whole sentences stay in the disc; an arc
 * is for naming, not for explaining.
 *
 * Every arc in the app goes through [TopArc] or [BottomArc], which is what makes
 * [CURVED_THAI] a real switch rather than a wish: one constant turns every arc
 * in the app back into straight text without touching a single screen.
 */
object ArcText {
    /**
     * Whether Thai is allowed on an arc.
     *
     * Curved text is laid out glyph by glyph along a tangent, and Thai stacks
     * tone marks above and vowels below a base consonant, with two leading
     * vowels (เ, โ) that have to be visually reordered before shaping. Latin
     * has none of that. This is verified on a real watch, not assumed — see the
     * checklist in the plan. If shaping breaks, set this to false: arcs then
     * fall back to straight, centred text in Thai only, and Latin and numeric
     * arcs keep their curve.
     */
    const val CURVED_THAI: Boolean = true
}

@Composable
private fun isThai(): Boolean = LocalConfiguration.current.locales[0].language == "th"

/**
 * True when this text can be drawn on the bezel: a round screen, and either not
 * Thai or Thai that has been cleared for curving.
 */
@Composable
fun canCurve(): Boolean = isRoundScreen() && (ArcText.CURVED_THAI || !isThai())

/**
 * Tracking is removed for Thai.
 *
 * The phone already makes this call for straight labels — see `labelStyle()` in
 * the app module, whose comment explains that Thai has no tracking tradition
 * and that spacing separates a base glyph from its marks. On an arc it is
 * worse: the tracking is applied along the tangent, so a cluster is pulled
 * apart around the curve rather than merely along a line.
 */
@Composable
private fun arcLetterSpacing(): TextUnit = if (isThai()) 0.sp else TextUnit.Unspecified

/** The status band at 12 o'clock. */
@Composable
fun TopArc(
    text: String,
    style: CurvedTextStyle,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    contentDescription: String? = null,
) {
    if (!canCurve()) {
        StraightArcFallback(text, contentDescription, color, Alignment.TopCenter, modifier)
        return
    }
    val letterSpacing = arcLetterSpacing()
    CurvedLayout(modifier = modifier) {
        curvedText(
            text = text,
            modifier = CurvedModifier
                .padding(radial = ARC_RADIAL_PADDING, angular = 0.dp)
                .semantics { this.contentDescription = contentDescription ?: text },
            maxSweepAngle = CurvedTextDefaults.StaticContentMaxSweepAngle,
            color = color,
            letterSpacing = letterSpacing,
            style = style,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The caption band at 6 o'clock. Never used on a screen that has an EdgeButton. */
@Composable
fun BottomArc(
    text: String,
    style: CurvedTextStyle,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    contentDescription: String? = null,
) {
    if (!canCurve()) {
        StraightArcFallback(text, contentDescription, color, Alignment.BottomCenter, modifier)
        return
    }
    val letterSpacing = arcLetterSpacing()
    CurvedLayout(
        modifier = modifier,
        anchor = 90f,
        angularDirection = CurvedDirection.Angular.Reversed,
    ) {
        curvedText(
            text = text,
            modifier = CurvedModifier
                .padding(radial = ARC_RADIAL_PADDING, angular = 0.dp)
                .semantics { this.contentDescription = contentDescription ?: text },
            maxSweepAngle = CurvedTextDefaults.StaticContentMaxSweepAngle,
            color = color,
            letterSpacing = letterSpacing,
            style = style,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The same label, in the same place, without the curve.
 *
 * Serves both the square-device case and the Thai fallback, so there is exactly
 * one branch to reason about rather than two.
 */
@Composable
private fun StraightArcFallback(
    text: String,
    contentDescription: String?,
    color: Color,
    alignment: Alignment,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = alignment) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = screenWidthFraction(FALLBACK_WIDTH_FRACTION))
                .padding(vertical = ARC_RADIAL_PADDING)
                .semantics { this.contentDescription = contentDescription ?: text },
        )
    }
}

/** Headroom for Thai marks above and below the base line of the arc. */
private val ARC_RADIAL_PADDING = 3.dp

private const val FALLBACK_WIDTH_FRACTION = 0.62f

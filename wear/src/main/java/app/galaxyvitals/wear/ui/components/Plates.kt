package app.galaxyvitals.wear.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import app.galaxyvitals.wear.ui.theme.Plate
import app.galaxyvitals.wear.ui.theme.PlateShape
import app.galaxyvitals.wear.ui.theme.captionTracking
import app.galaxyvitals.wear.ui.theme.labelTracking
import app.galaxyvitals.wear.ui.theme.plateWidth
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The pieces every screen is built from.
 *
 * A plate is a rectangle at one of the three widths [Plate] defines, and a
 * screen is a stack of them. There are no containers here: a caption is text on
 * the ground, a rule is one dp of [MaterialTheme]'s outline, and only the two
 * things you actually press carry a fill and a corner.
 */

/** A plate at its tier's width, centred. */
@Composable
fun Modifier.plate(tier: Plate): Modifier = this.width(plateWidth(tier))

/**
 * The status line, at the top of every screen.
 *
 * This is what replaced the curved bezel label. It says one short thing — a
 * link state, a phase, a count — in the app's machine voice, and it carries no
 * colour: a red status band would spend the one hue that means "irregular
 * rhythm" on a Bluetooth link.
 */
@Composable
fun StatusBand(
    text: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = labelTracking(),
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .plate(Plate.Band)
            .semantics { this.contentDescription = contentDescription ?: text },
    )
}

/** The name of the thing underneath it. Never a sentence. */
@Composable
fun PlateCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = captionTracking(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** One dp of outline. The whole separator. */
@Composable
fun PlateRule(modifier: Modifier = Modifier, tier: Plate = Plate.Action) {
    Box(
        modifier
            .plate(tier)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

/**
 * The one verb on a screen, as a bar rather than a wedge.
 *
 * Wear's own `EdgeButton` is a shape cut out of a circle, which is exactly the
 * round-screen idea this design takes out. The bar sits at the Action tier and
 * keeps a full 48 dp target: the plate grid frees the height for it, so there
 * is no reason to shrink it.
 */
@Composable
fun ActionPlate(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
) {
    Button(
        onClick = onClick,
        shape = PlateShape,
        colors = if (filled) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
        border = if (filled) null else ButtonDefaults.outlinedButtonBorder(enabled = true),
        contentPadding = ACTION_PADDING,
        modifier = modifier
            .width(ACTION_WIDTH_FRACTION.let { plateWidth(Plate.Action) * it })
            // Exactly the target, not a minimum: the column above reserves this
            // much and a taller button would be drawn over the last plate.
            .height(TOUCH_TARGET),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Two destinations, side by side, with ground between them.
 *
 * Not a segmented control: History and Settings are two different places, so
 * they are two plates with air between them. The either/or pair in Settings is
 * the opposite case and uses [SegmentedPlate].
 */
@Composable
fun DestinationPair(
    firstLabel: String,
    onFirst: () -> Unit,
    secondLabel: String,
    onSecond: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.plate(Plate.Core),
        horizontalArrangement = Arrangement.spacedBy(PAIR_GAP),
    ) {
        DestinationPlate(firstLabel, onFirst, Modifier.weight(1f))
        DestinationPlate(secondLabel, onSecond, Modifier.weight(1f))
    }
}

@Composable
private fun DestinationPlate(label: String, onClick: () -> Unit, modifier: Modifier) {
    Button(
        onClick = onClick,
        shape = PlateShape,
        colors = ButtonDefaults.filledTonalButtonColors(),
        // Half the default side padding: two labels share one plate here, and at
        // the default "ประวัติ" no longer fits and ellipsises to "ประ…" — a menu
        // item that cannot say its own name.
        contentPadding = PAIR_PADDING,
        modifier = modifier.height(TOUCH_TARGET),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * One either/or, as a single plate split by a rule.
 *
 * The opposite case to [DestinationPair]: left and right wrist are two states
 * of one setting, not two places, so they share a plate and the 1 dp rule
 * between them is the choice. The outer corners round; the inner edges stay
 * hard against the rule, which is what keeps the pair reading as one object.
 */
@Composable
fun SegmentedPlate(
    firstLabel: String,
    firstSelected: Boolean,
    onFirst: () -> Unit,
    secondLabel: String,
    onSecond: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .plate(Plate.Core)
            .height(TOUCH_TARGET)
            .clip(PlateShape)
            .background(MaterialTheme.colorScheme.outline),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Segment(firstLabel, firstSelected, onFirst, Modifier.weight(1f))
        Segment(secondLabel, !firstSelected, onSecond, Modifier.weight(1f))
    }
}

@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier,
) {
    val background =
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainer
    val content =
        if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(background)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Wear's minimum touch target, and the height every pressable here takes. */
val TOUCH_TARGET = 48.dp

private val PAIR_GAP = 10.dp
private val PAIR_PADDING = PaddingValues(horizontal = 8.dp)
private val ACTION_PADDING = PaddingValues(horizontal = 10.dp)

/**
 * The action bar runs a shade under its tier's full width.
 *
 * The tier is solved for a plate centred on the screen; the bar sits at the
 * very bottom of the column, so its own lower corners need a little more room
 * than the tier alone guarantees.
 */
private const val ACTION_WIDTH_FRACTION = 0.97f

/**
 * The time of day, updated on the minute.
 *
 * Wear's `TimeText` draws a curve on a round screen, which is the one thing
 * this redesign removes, so the clock is read here and shown in the status band
 * as ordinary straight text. The pattern follows the wearer's own 12/24 hour
 * setting rather than the app's guess at it.
 */
@Composable
fun rememberWatchClock(): String {
    val context = LocalContext.current
    val is24Hour = DateFormat.is24HourFormat(context)
    val format = remember(is24Hour) {
        SimpleDateFormat(if (is24Hour) "HH:mm" else "h:mm a", Locale.getDefault())
    }
    var now by remember(format) { mutableStateOf(format.format(Date())) }
    LaunchedEffect(format) {
        while (true) {
            val millis = System.currentTimeMillis()
            now = format.format(Date(millis))
            // Wake on the minute boundary rather than on a fixed interval, so
            // the displayed minute is never a second stale.
            delay(MINUTE_MS - millis % MINUTE_MS)
        }
    }
    return now
}

private const val MINUTE_MS = 60_000L

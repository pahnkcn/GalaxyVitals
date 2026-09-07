package app.galaxyvitals.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import app.galaxyvitals.data.protocol.ParsedEcgFile
import app.galaxyvitals.wear.R
import app.galaxyvitals.wear.ui.components.ActionPlate
import app.galaxyvitals.wear.ui.components.DestinationPair
import app.galaxyvitals.wear.ui.components.PlateCaption
import app.galaxyvitals.wear.ui.components.StatusBand
import app.galaxyvitals.wear.ui.components.TOUCH_TARGET
import app.galaxyvitals.wear.ui.components.plate
import app.galaxyvitals.wear.ui.components.rememberWatchClock
import app.galaxyvitals.wear.ui.theme.Plate
import app.galaxyvitals.wear.ui.theme.safeVerticalInset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Whether the phone is there, the last answer, then the one thing to do.
 *
 * Four plates and a bar. The link line comes first because it qualifies
 * everything under it: a reading on a watch that cannot reach its phone is a
 * reading that has not arrived anywhere yet. It shares the status band with the
 * clock, because between them they are the two facts that are true of the
 * screen rather than of the recording.
 *
 * The rate is set flush left against the plate's own edge with its timestamp
 * flush right, so the row states one measured fact and names it — the rule the
 * whole layout is built on. Centring it would put the number in the middle of a
 * disc, which is the round idea this replaces.
 *
 * There is no rule under the status band, unlike the other screens. Home is the
 * one stack that runs the full height of the face — status, rate, two
 * destinations and a pinned bar, all at 48 dp targets and 10 dp apart — and the
 * arithmetic does not leave room for a hairline that the 10 dp of ground is
 * already doing the work of. `PlateGeometryTest` is what says so.
 *
 * Starting a recording is the only verb here, so it takes the bottom of the
 * screen as a pinned bar: always in the same place, never scrolled away, and a
 * full 48 dp target. History and Settings sit above it as two plates with
 * ground between them, because they are two different places rather than one
 * either/or.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onStart: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    val inset = safeVerticalInset()
    val scrollState = rememberScrollState()

    ScreenScaffold(scrollInfoProvider = null) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // The pinned bar's own room is reserved here rather than
                    // drawn over: the column stops where the bar starts.
                    .padding(top = inset, bottom = inset + TOUCH_TARGET + PLATE_GAP)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(PLATE_GAP),
            ) {
                StatusBand(phoneStatusText(state))
                LatestReading(state.latest)
                DestinationPair(
                    firstLabel = stringResource(R.string.wear_history),
                    onFirst = onHistory,
                    secondLabel = stringResource(R.string.wear_settings),
                    onSecond = onSettings,
                )
            }
            ActionPlate(
                label = stringResource(R.string.wear_start),
                onClick = onStart,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = inset),
            )
        }
    }
}

/**
 * The link and the time, in one line.
 *
 * Stated in both directions rather than only on failure: "is it connected" is a
 * question people ask of a watch, and an indicator that is only ever absent
 * cannot be checked. A watch that cannot reach its phone owns the band alone —
 * the time of day is not the news at that moment.
 */
@Composable
private fun phoneStatusText(state: HomeUiState): String = when {
    state.checkingPhone -> stringResource(R.string.wear_phone_status_checking)
    state.phones.isEmpty() -> stringResource(R.string.wear_phone_status_offline)
    else -> stringResource(
        R.string.wear_status_pair,
        stringResource(R.string.wear_phone_status_linked),
        rememberWatchClock(),
    )
}

/**
 * The hero: value left, label right.
 *
 * The empty state keeps every position and swaps only the value, so nothing on
 * the screen moves the moment a first reading lands.
 */
@Composable
private fun LatestReading(latest: ParsedEcgFile?, modifier: Modifier = Modifier) {
    val format = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val stamp = latest?.let { format.format(Date(it.tsStartMs)) }
    val spoken = stamp
        ?.let { stringResource(R.string.wear_last_reading, it) }
        ?: stringResource(R.string.wear_no_reading)

    Row(
        modifier = modifier
            .plate(Plate.Core)
            .semantics { contentDescription = spoken },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = latest?.let { WatchSessionBpm.displayBpmText(it) } ?: EM_DASH,
                // Two steps down from the largest numeral. On the real
                // SM-L350 face — 225 dp, not the 240 a 480 px panel would
                // imply — numeralLarge plus two 48 dp targets overruns the
                // usable height and clips the menu row. See PlateGeometryTest.
                // An em dash set at numeral weight draws as a solid bar and
                // reads as a broken progress indicator, so the empty state
                // keeps the slot but drops to the small numeral for it.
                style = if (latest == null) {
                    MaterialTheme.typography.numeralSmall
                } else {
                    MaterialTheme.typography.numeralMedium
                },
                color = if (latest == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
            )
            if (latest != null) {
                Text(
                    text = stringResource(R.string.wear_unit_bpm),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = UNIT_GAP, bottom = UNIT_BASELINE),
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            PlateCaption(stringResource(R.string.wear_label_last))
            Text(
                text = stamp ?: stringResource(R.string.wear_value_none),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The one gap in the app. Everything on a screen is this far from its neighbour. */
internal val PLATE_GAP = 10.dp

private val UNIT_GAP = 4.dp

/** Lifts the unit off the numeral's own descender line so the two share a baseline. */
private val UNIT_BASELINE = 4.dp

private const val EM_DASH = "—"

package app.galaxyvitals.wear.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberOverscrollEffect
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
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.curvedText
import androidx.wear.compose.material3.timeTextCurvedText
import androidx.wear.compose.material3.timeTextSeparator
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import app.galaxyvitals.data.protocol.ParsedEcgFile
import app.galaxyvitals.wear.R
import app.galaxyvitals.wear.ui.theme.canCurve
import app.galaxyvitals.wear.ui.theme.listSideMargin
import app.galaxyvitals.wear.ui.theme.screenWidthFraction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Whether the phone is there, the last answer, then the one thing to do.
 *
 * The link line comes first because it qualifies everything under it: a reading
 * on a watch that cannot reach its phone is a reading that has not arrived
 * anywhere yet.
 *
 * The rate sits in the middle of the disc where the screen is widest, stacked
 * over its unit rather than beside it — a row of number-then-unit spends that
 * chord on a word that never changes and pushes the number off centre. Starting
 * a recording is the only verb here, so it takes the bottom of the circle as an
 * EdgeButton, a shape cut from the screen instead of a pill laid on it. History
 * and Settings sit side by side in the middle band, where all four of their
 * corners stay clear of the bezel.
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
    // The list rests with its anchor item centred, and the library anchors on
    // index 1 by default — here the menu row, which pushes the rate above it up
    // under the status arc. The reading is what should sit in the middle.
    val columnState = rememberTransformingLazyColumnState(initialAnchorItemIndex = 0)
    val transformationSpec = rememberTransformationSpec()
    val overscroll = rememberOverscrollEffect()

    ScreenScaffold(
        scrollState = columnState,
        timeText = { PhoneLinkTimeText(state) },
        edgeButton = {
            EdgeButton(
                onClick = onStart,
                buttonSize = EdgeButtonSize.Small,
                // A drag that starts on the button still scrolls the list.
                modifier = Modifier.scrollable(
                    columnState,
                    orientation = Orientation.Vertical,
                    reverseDirection = true,
                    overscrollEffect = overscroll,
                ),
            ) {
                Text(
                    stringResource(R.string.wear_start),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) { contentPadding ->
        // No extra bottom padding: the scaffold already reserves the button's
        // wedge, and adding more would count it twice.
        TransformingLazyColumn(
            state = columnState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize().padding(horizontal = listSideMargin()),
        ) {
            val latest = state.latest
            if (latest == null) {
                // With nothing to report the first item is a heading, so it uses
                // the component that already knows how to sit under the arc.
                item {
                    ListHeader(
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                            .minimumVerticalContentPadding(
                                ListHeaderDefaults.minimumTopListContentPadding,
                            ),
                        transformation = SurfaceTransformation(transformationSpec),
                    ) {
                        Text(
                            text = stringResource(R.string.wear_no_reading),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                item {
                    // Deliberately no SurfaceTransformation: that paints a rounded
                    // container, and a filled rectangle behind the hero across the
                    // widest band is the exact artefact this redesign removes.
                    LatestReading(
                        latest = latest,
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                    )
                }
            }
            item {
                val historySource = remember { MutableInteractionSource() }
                val settingsSource = remember { MutableInteractionSource() }
                ButtonGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .minimumVerticalContentPadding(
                            ButtonDefaults.minimumVerticalListContentPadding,
                        ),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Button(
                        onClick = onHistory,
                        modifier = Modifier.height(MENU_BUTTON_HEIGHT).animateWidth(historySource),
                        interactionSource = historySource,
                        contentPadding = MENU_BUTTON_PADDING,
                    ) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.wear_history),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Button(
                        onClick = onSettings,
                        modifier = Modifier.height(MENU_BUTTON_HEIGHT).animateWidth(settingsSource),
                        interactionSource = settingsSource,
                        contentPadding = MENU_BUTTON_PADDING,
                    ) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.wear_settings),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Whether the phone is there, on the band above everything else.
 *
 * The link belongs outside the scrolling list. Inside it, it is the first thing
 * to leave the screen and the wearer reads a rate without knowing whether it
 * has gone anywhere; on the arc it is always the top line, and it costs the
 * disc no height at all.
 *
 * It is stated in both directions rather than only on failure: "is it connected"
 * is a question people ask of a watch, and an indicator that is only ever absent
 * cannot be checked. The words are short because they share the arc with the
 * clock, and they carry no colour — a red arc would spend the one hue that
 * means "irregular rhythm" on a Bluetooth link.
 */
@Composable
private fun PhoneLinkTimeText(state: HomeUiState) {
    val label = when {
        state.checkingPhone -> stringResource(R.string.wear_phone_status_checking)
        state.phones.isNotEmpty() -> stringResource(R.string.wear_phone_status_linked)
        else -> stringResource(R.string.wear_phone_arc_offline)
    }
    if (!canCurve()) {
        // Straight fallback: the clock keeps the band, and the status sits with
        // the content instead of being dropped.
        TimeText()
        return
    }
    val style = MaterialTheme.typography.arcMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    if (state.phones.isEmpty() && !state.checkingPhone) {
        // A missing phone owns the band alone. Keeping the clock beside it
        // stretches the sweep down both sides of the screen until it crowds the
        // rate underneath — and the time of day is not the news here.
        TimeText { curvedText(label, color = color, style = style) }
        return
    }
    TimeText { time ->
        curvedText(label, color = color, style = style)
        timeTextSeparator()
        timeTextCurvedText(time)
    }
}

@Composable
private fun LatestReading(latest: ParsedEcgFile, modifier: Modifier = Modifier) {
    val format = rememberTimeFormat()
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val stamp = format.format(Date(latest.tsStartMs))
        val spoken = stringResource(R.string.wear_last_reading, stamp)
        Text(
            text = WatchSessionBpm.displayBpmText(latest),
            // One step down from the largest numeral: extra-large reaches high
            // enough to crowd the status arc, and the list fixes the item's
            // height so neither padding nor content can push it clear.
            style = MaterialTheme.typography.numeralLarge,
            maxLines = 1,
            modifier = Modifier
                .widthIn(max = screenWidthFraction(HERO_FRACTION))
                .semantics { contentDescription = spoken },
        )
        Text(
            text = stringResource(R.string.wear_unit_bpm),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        // Time only. The date prefix was the wide line that ran into the bezel,
        // and it sits below the number now rather than above it, where the
        // circle is still wide.
        Text(
            text = stamp,
            style = MaterialTheme.typography.bodyExtraSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = screenWidthFraction(STAMP_FRACTION)),
        )
    }
}

@Composable
private fun rememberTimeFormat(): SimpleDateFormat =
    remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

/** Wear's minimum touch target, and no more than that. */
private val MENU_BUTTON_HEIGHT = 48.dp

/**
 * Half the default side padding.
 *
 * Two buttons share one chord here, so the padding is subtracted twice from
 * whatever is left for the word. At the default, "ประวัติ" no longer fits and
 * ellipsises to "ประ…" — a menu item that cannot say its own name.
 */
private val MENU_BUTTON_PADDING = PaddingValues(horizontal = 8.dp, vertical = 0.dp)

private const val HERO_FRACTION = 0.72f
private const val STAMP_FRACTION = 0.62f

package app.galaxyvitals.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import app.galaxyvitals.wear.R
import app.galaxyvitals.wear.ui.components.ActionPlate
import app.galaxyvitals.wear.ui.components.EcgWaveformMini
import app.galaxyvitals.wear.ui.components.HomeKeyHint
import app.galaxyvitals.wear.ui.components.PlateCaption
import app.galaxyvitals.wear.ui.components.PlateRule
import app.galaxyvitals.wear.ui.components.StatusBand
import app.galaxyvitals.wear.ui.components.TOUCH_TARGET
import app.galaxyvitals.wear.ui.components.TickRail
import app.galaxyvitals.wear.ui.components.plate
import app.galaxyvitals.wear.ui.theme.Plate
import app.galaxyvitals.wear.ui.theme.labelTracking
import app.galaxyvitals.wear.ui.theme.rememberBeatPulse
import app.galaxyvitals.wear.ui.theme.safeVerticalInset
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The measurement, as a strip chart rather than a dial.
 *
 * Status at the top, the reading in the middle, the rail underneath it, and
 * whatever there is to do pinned at the bottom. Every one of them is a plate at
 * a tier width, so the stack reads as one column with one left margin.
 *
 * The rail runs in two directions on purpose. The arming countdown **drains**
 * over three wide ticks and the recording **fills** over thirty narrow ones: a
 * fuse burning down, then a jar filling. Making both go the same way would be
 * tidier and would say the wrong thing.
 */
@Composable
fun MeasureScreen(
    state: MeasureUiState,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    onRequestPermission: () -> Unit = {},
    onResolve: () -> Unit = {},
) {
    // material3 has a KeepScreenOn() but it is internal in 1.6.2, so the flag is
    // held here and the previous value restored when the screen goes away.
    val view = LocalView.current
    DisposableEffect(view) {
        val previousKeepScreenOn = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previousKeepScreenOn }
    }

    val action = state.action()
    val inset = safeVerticalInset()
    val scrollState = rememberScrollState()

    ScreenScaffold(scrollInfoProvider = null) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = inset,
                        bottom = if (action == null) inset else inset + TOUCH_TARGET + PLATE_GAP,
                    )
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(PLATE_GAP),
            ) {
                StatusBand(statusText(state.status))
                PlateRule()
                PhaseContent(state)
                PhaseRail(state)
            }
            if (action != null) {
                ActionPlate(
                    label = stringResource(action.label),
                    onClick = when (action) {
                        MeasureAction.Retry -> onRetry
                        MeasureAction.Done -> onDone
                        MeasureAction.Permission -> onRequestPermission
                        MeasureAction.Resolve -> onResolve
                    },
                    // A failure is not a verdict, so the way back is offered
                    // rather than urged: the outlined plate reads as available
                    // without competing with the reading above it.
                    filled = action != MeasureAction.Retry,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = inset),
                )
            }
        }
    }
}

/** What the wearer can do from this phase, if anything. */
private enum class MeasureAction(val label: Int) {
    Retry(R.string.wear_record_again),
    Done(R.string.wear_done),
    Permission(R.string.wear_allow_sensors),
    Resolve(R.string.wear_open_samsung_health),
}

private fun MeasureUiState.action(): MeasureAction? = when (phase) {
    MeasurePhase.Failed, MeasurePhase.Unavailable -> MeasureAction.Retry
    MeasurePhase.Success -> MeasureAction.Done
    MeasurePhase.PermissionRequired -> MeasureAction.Permission
    MeasurePhase.ResolutionRequired -> MeasureAction.Resolve
    else -> null
}

@Composable
private fun PhaseContent(state: MeasureUiState) {
    when (state.phase) {
        MeasurePhase.Connecting, MeasurePhase.Saving ->
            Captured(stringResource(R.string.wear_label_captured))

        MeasurePhase.PreparingHeartRate -> HeartRateStack(state.bpm)

        // A drawing of the watch in your hand, not a piece of the interface —
        // which is why this one thing is still round.
        MeasurePhase.WaitingForContact -> HomeKeyHint(
            Modifier.plate(Plate.Core).aspectRatio(HINT_ASPECT),
        )

        MeasurePhase.ArmedCountdown -> Text(
            text = "%02d".format(state.remainingSec),
            style = MaterialTheme.typography.numeralExtraLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.plate(Plate.Core),
        )

        MeasurePhase.Recording -> Column(
            modifier = Modifier.plate(Plate.Core),
            verticalArrangement = Arrangement.spacedBy(PLATE_GAP),
        ) {
            HeartRateStack(state.bpm)
            EcgWaveformMini(state.waveform)
        }

        // The payoff. The rate survives the save, so the screen that says the
        // recording is finished can also say what it found.
        MeasurePhase.Success -> Column(
            modifier = Modifier.plate(Plate.Core),
            verticalArrangement = Arrangement.spacedBy(SUB_GAP),
        ) {
            Reading(
                caption = stringResource(R.string.wear_label_result),
                value = state.bpm.estimate?.bpm?.roundToInt()?.toString() ?: EM_DASH,
                unit = stringResource(R.string.wear_unit_bpm),
            )
            state.error?.let { Body(messageText(it)) }
        }

        MeasurePhase.PermissionRequired ->
            Body(stringResource(R.string.wear_permission_body))

        MeasurePhase.ResolutionRequired ->
            Body(state.error?.let { messageText(it) } ?: stringResource(R.string.wear_samsung_setup))

        // Failures are not verdicts. The app's red means "irregular rhythm" and
        // the watch never computes one, so a failed recording says how far it
        // got in the ordinary neutral and lets the rail carry the rest.
        MeasurePhase.Failed, MeasurePhase.Unavailable -> Column(
            modifier = Modifier.plate(Plate.Core),
            verticalArrangement = Arrangement.spacedBy(SUB_GAP),
        ) {
            Reading(
                caption = stringResource(R.string.wear_label_stopped_at),
                value = elapsedSec(state).toString(),
                unit = stringResource(R.string.wear_of_seconds, TickRail.RECORD_TICKS),
                large = false,
            )
            Body(state.error?.let { messageText(it) } ?: stringResource(R.string.wear_unavailable))
        }
    }
}

/**
 * The rail, and the two marks that say what its ticks are worth.
 *
 * Phases that are not counting anything get no rail at all rather than an empty
 * one: a track with nothing on it still reads as a thing that should be moving.
 */
@Composable
private fun PhaseRail(state: MeasureUiState) {
    when (state.phase) {
        MeasurePhase.Connecting, MeasurePhase.PreparingHeartRate, MeasurePhase.Saving ->
            RailBlock(
                start = stringResource(R.string.wear_rail_saving),
                end = null,
            ) {
                TickRail.Working(
                    contentDescription = stringResource(R.string.wear_rail_saving),
                )
            }

        MeasurePhase.ArmedCountdown -> RailBlock(
            start = stringResource(R.string.wear_rail_arming),
            end = stringResource(R.string.wear_seconds_short, state.remainingSec),
        ) {
            TickRail.Determinate(
                total = TickRail.ARM_TICKS,
                lit = TickRail.remainingTicks(TickRail.ARM_TICKS, state.remainingSec),
                contentDescription = stringResource(R.string.wear_seconds_left, state.remainingSec),
            )
        }

        MeasurePhase.Recording -> RailBlock(
            start = stringResource(R.string.wear_rail_zero),
            end = stringResource(R.string.wear_seconds_left, state.remainingSec),
        ) {
            TickRail.Determinate(
                total = TickRail.RECORD_TICKS,
                lit = TickRail.elapsedTicks(TickRail.RECORD_TICKS, state.remainingSec),
                contentDescription = stringResource(R.string.wear_seconds_left, state.remainingSec),
            )
        }

        // A full rail, in bone. Green here would spend the colour that means
        // "regular rhythm" on a file that merely finished writing.
        MeasurePhase.Success -> RailBlock(
            start = stringResource(R.string.wear_rail_complete),
            end = stringResource(R.string.wear_seconds_short, TickRail.RECORD_TICKS),
        ) {
            TickRail.Complete(
                contentDescription = stringResource(R.string.wear_rail_complete),
            )
        }

        // The rail stops where the recording did.
        MeasurePhase.Failed -> RailBlock(start = null, end = null) {
            TickRail.Determinate(
                total = TickRail.RECORD_TICKS,
                lit = elapsedSec(state),
            )
        }

        MeasurePhase.WaitingForContact,
        MeasurePhase.Unavailable,
        MeasurePhase.PermissionRequired,
        MeasurePhase.ResolutionRequired,
        -> Unit
    }
}

@Composable
private fun RailBlock(start: String?, end: String?, rail: @Composable () -> Unit) {
    Column(Modifier.plate(Plate.Core)) {
        rail()
        if (start == null && end == null) return@Column
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = RAIL_LABEL_GAP),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RailMark(start.orEmpty())
            RailMark(end.orEmpty())
        }
    }
}

@Composable
private fun RailMark(text: String) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = labelTracking(),
        maxLines = 1,
    )
}

/** Caption over value, both flush left: the same shape as Home's hero. */
@Composable
private fun Reading(caption: String, value: String, unit: String, large: Boolean = true) {
    Column {
        PlateCaption(caption)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = if (large) {
                    MaterialTheme.typography.numeralLarge
                } else {
                    MaterialTheme.typography.numeralMedium
                },
                maxLines = 1,
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun Captured(caption: String) {
    Reading(
        caption = caption,
        value = TickRail.RECORD_TICKS.toString(),
        unit = stringResource(R.string.wear_unit_seconds),
    )
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.plate(Plate.Core),
    )
}

/** The rate, with the heart beating at that rate. */
@Composable
private fun HeartRateStack(bpm: LiveBpmState, modifier: Modifier = Modifier) {
    val estimate = bpm.estimate.takeIf { bpm.availability == LiveBpmAvailability.RELIABLE }
    val sourceLabel = stringResource(
        WearText.heartRateSourceRes(bpm.estimate?.epoch, bpm.estimate?.source),
    )
    // Only the capture's own rate drives the beat; a preflight reading is a
    // number from before the recording started, not the current pulse.
    val pulse by rememberBeatPulse(estimate?.takeIf { it.epoch == BpmEpoch.CAPTURE }?.bpm)

    Row(modifier.plate(Plate.Core), verticalAlignment = Alignment.Bottom) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = sourceLabel,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .padding(end = 5.dp, bottom = 6.dp)
                .size(12.dp)
                .graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                },
        )
        Text(
            text = estimate?.bpm?.roundToInt()?.toString() ?: EM_DASH,
            style = MaterialTheme.typography.numeralMedium,
            maxLines = 1,
        )
        Text(
            text = stringResource(R.string.wear_unit_bpm),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
    }
}

/** Status and error text arrive as tokens; the screen shows them translated. */
@Composable
private fun statusText(status: String): String =
    WearText.statusRes(status)?.let { stringResource(it) } ?: status

@Composable
private fun messageText(message: String): String =
    WearText.messageRes(message)?.let { stringResource(it) } ?: message

private fun elapsedSec(state: MeasureUiState): Int =
    TickRail.elapsedTicks(TickRail.RECORD_TICKS, state.remainingSec)

/** Roughly 4:3, which is what the hardware drawing needs and no more. */
private const val HINT_ASPECT = 1.38f

private val RAIL_LABEL_GAP = 4.dp
private val SUB_GAP = 6.dp

private const val EM_DASH = "—"

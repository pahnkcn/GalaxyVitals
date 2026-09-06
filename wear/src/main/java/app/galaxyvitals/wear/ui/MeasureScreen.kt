package app.galaxyvitals.wear.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.curvedText
import app.galaxyvitals.wear.R
import app.galaxyvitals.wear.ui.components.EcgWaveformMini
import app.galaxyvitals.wear.ui.components.HomeKeyHint
import app.galaxyvitals.wear.ui.components.RimProgress
import app.galaxyvitals.wear.ui.theme.BottomArc
import app.galaxyvitals.wear.ui.theme.canCurve
import app.galaxyvitals.wear.ui.theme.rememberBeatPulse
import app.galaxyvitals.wear.ui.theme.rememberReduceMotion
import app.galaxyvitals.wear.ui.theme.screenWidthFraction
import kotlin.math.roundToInt

/**
 * The measurement, as a dial.
 *
 * The rim carries the seconds, the top arc carries the status, the middle of
 * the disc carries the rate and the trace, and the bottom of the circle carries
 * whatever there is to do. Nothing here is a full-width row, because on a round
 * screen a full-width row is only full width at one height.
 *
 * The rim runs in two directions on purpose. The arming countdown **drains**
 * over three seconds and the recording **fills** over thirty: a fuse burning
 * down, then a jar filling. Making both go the same way would be tidier and
 * would say the wrong thing.
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
    // The status owns the top arc alone: during a thirty-second capture the time
    // of day is not what the wearer needs, and dropping the clock roughly
    // doubles the sweep a long status has to fit into.
    ScreenScaffold(
        scrollInfoProvider = null,
        timeText = { MeasureStatusArc(state) },
    ) { contentPadding ->
        Box(Modifier.fillMaxSize()) {
            MeasureRim(state)

            Box(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                // Lifted clear of the EdgeButton when there is one to clear.
                contentAlignment = BiasAlignment(0f, if (action != null) ACTION_BIAS else 0f),
            ) {
                PhaseContent(state, Modifier.widthIn(max = screenWidthFraction(CONTENT_FRACTION)))
            }

            if (action != null) {
                EdgeButton(
                    onClick = when (action) {
                        MeasureAction.Retry -> onRetry
                        MeasureAction.Done -> onDone
                        MeasureAction.Permission -> onRequestPermission
                        MeasureAction.Resolve -> onResolve
                    },
                    buttonSize = EdgeButtonSize.Small,
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    Text(stringResource(action.label), maxLines = 1)
                }
            } else {
                MeasureBottomArc(state)
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
private fun MeasureStatusArc(state: MeasureUiState) {
    // Waiting for contact is the one phase the hint drawing owns outright: a
    // heading above it would compete with the arrow pointing at the button.
    if (state.phase == MeasurePhase.WaitingForContact) return
    val text = statusText(state.status)
    val style = MaterialTheme.typography.arcMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    if (canCurve()) {
        TimeText { curvedText(text, color = color, style = style) }
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.widthIn(max = screenWidthFraction(CONTENT_FRACTION)),
            )
        }
    }
}

@Composable
private fun MeasureBottomArc(state: MeasureUiState) {
    val style = MaterialTheme.typography.arcSmall
    when (state.phase) {
        MeasurePhase.PreparingHeartRate ->
            BottomArc(stringResource(R.string.wear_keep_still), style)

        MeasurePhase.WaitingForContact ->
            BottomArc(
                text = stringResource(R.string.wear_arc_touch_button),
                style = style,
                contentDescription = stringResource(R.string.wear_touch_top_button),
            )

        MeasurePhase.Recording ->
            BottomArc(
                text = stringResource(R.string.wear_arc_seconds_left, state.remainingSec),
                style = style,
                contentDescription = stringResource(R.string.wear_seconds_left, state.remainingSec),
            )

        else -> Unit
    }
}

@Composable
private fun MeasureRim(state: MeasureUiState) {
    val reduce = rememberReduceMotion()
    val target = when (state.phase) {
        MeasurePhase.ArmedCountdown ->
            (state.remainingSec.toFloat() / ARM_SECONDS).coerceIn(0f, 1f)
        MeasurePhase.Recording ->
            1f - (state.remainingSec.toFloat() / RECORD_SECONDS).coerceIn(0f, 1f)
        else -> 0f
    }
    // One second per tick, unsmoothed: the arc keeps the clock's own time.
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(if (reduce) 0 else 1_000, easing = { it }),
        label = "rim",
    )
    when (state.phase) {
        MeasurePhase.Connecting, MeasurePhase.PreparingHeartRate, MeasurePhase.Saving ->
            RimProgress.Working()
        MeasurePhase.ArmedCountdown, MeasurePhase.Recording ->
            RimProgress.Determinate { progress }
        MeasurePhase.Success -> RimProgress.Complete()
        else -> RimProgress.Idle()
    }
}

@Composable
private fun PhaseContent(state: MeasureUiState, modifier: Modifier) {
    when (state.phase) {
        MeasurePhase.Connecting, MeasurePhase.Saving -> Unit

        MeasurePhase.PreparingHeartRate -> HeartRateStack(state.bpm, modifier)

        MeasurePhase.WaitingForContact -> HomeKeyHint(Modifier.fillMaxSize())

        MeasurePhase.ArmedCountdown -> Text(
            text = "%02d".format(state.remainingSec),
            style = MaterialTheme.typography.numeralExtraLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = modifier,
        )

        MeasurePhase.Recording -> Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HeartRateStack(state.bpm, Modifier)
            EcgWaveformMini(state.waveform)
        }

        // The payoff. The rate survives the save, so the screen that says the
        // recording is finished can also say what it found.
        MeasurePhase.Success -> Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = state.bpm.estimate?.bpm?.roundToInt()?.toString() ?: "—",
                style = MaterialTheme.typography.numeralExtraLarge,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.wear_unit_bpm),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.error?.let { Body(messageText(it), Modifier.padding(top = 4.dp)) }
        }

        MeasurePhase.PermissionRequired ->
            Body(stringResource(R.string.wear_permission_body), modifier)

        MeasurePhase.ResolutionRequired ->
            Body(state.error?.let { messageText(it) } ?: stringResource(R.string.wear_samsung_setup), modifier)

        // Failures are not verdicts. The app's red means "irregular rhythm" and
        // the watch never computes one, so a failed recording says so in the
        // ordinary neutral and lets the button carry the weight.
        MeasurePhase.Failed, MeasurePhase.Unavailable -> Body(
            text = state.error?.let { messageText(it) } ?: stringResource(R.string.wear_unavailable),
            modifier = modifier,
        )
    }
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

/** The rate, stacked over its unit, with the heart beating at that rate. */
@Composable
private fun HeartRateStack(bpm: LiveBpmState, modifier: Modifier) {
    val estimate = bpm.estimate.takeIf { bpm.availability == LiveBpmAvailability.RELIABLE }
    val sourceLabel = stringResource(
        WearText.heartRateSourceRes(bpm.estimate?.epoch, bpm.estimate?.source),
    )
    // Only the capture's own rate drives the beat; a preflight reading is a
    // number from before the recording started, not the current pulse.
    val pulse by rememberBeatPulse(estimate?.takeIf { it.epoch == BpmEpoch.CAPTURE }?.bpm)

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = sourceLabel,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .size(14.dp)
                .graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                },
        )
        Text(
            text = estimate?.bpm?.roundToInt()?.toString() ?: "—",
            style = MaterialTheme.typography.numeralLarge,
            maxLines = 1,
        )
        Text(
            text = stringResource(R.string.wear_unit_bpm),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
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

/** The contract's own capture length, and the arming window before it. */
private const val RECORD_SECONDS = 30
private const val ARM_SECONDS = 3

/** How far the disc lifts to leave the EdgeButton its wedge. */
private const val ACTION_BIAS = -0.12f

private const val CONTENT_FRACTION = 0.66f

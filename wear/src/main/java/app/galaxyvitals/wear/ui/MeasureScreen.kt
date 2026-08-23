package app.galaxyvitals.wear.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import app.galaxyvitals.wear.ui.components.EcgWaveformMini
import kotlinx.coroutines.delay

@Composable
fun MeasureScreen(
    state: MeasureUiState,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(view) {
        val previousKeepScreenOn = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previousKeepScreenOn }
    }

    ScreenScaffold { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            Text(
                state.status,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
            when (state.phase) {
                MeasurePhase.Connecting, MeasurePhase.Warmup -> {
                    CircularProgressIndicator()
                }
                MeasurePhase.Ready, MeasurePhase.LeadOff -> {
                    Text(
                        if (state.phase == MeasurePhase.LeadOff) "Finger on the button" else "Hold still",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (state.liveMv.size >= 2) {
                        EcgWaveformMini(state.liveMv, Modifier.fillMaxWidth())
                    }
                }
                MeasurePhase.Recording -> RecordingReadout(state, Modifier.weight(1f))
                MeasurePhase.Saving -> {
                    CircularProgressIndicator()
                    Text(
                        "Keeping this recording safe",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                MeasurePhase.Success -> {
                    Text(
                        if (state.error == null) "On your phone" else "Saved on watch",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    state.error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text("Done")
                    }
                }
                MeasurePhase.Failed, MeasurePhase.Unavailable -> {
                    Text(
                        state.error ?: "Unavailable",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text("Record again")
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingReadout(
    state: MeasureUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LiveHeartRateReadout(state.hrBpm)
        Spacer(Modifier.weight(1f))
        EcgWaveformMini(state.liveMv, Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        Text(
            "%02d".format(state.remainingSec),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LiveHeartRateReadout(bpm: Int?) {
    val pulseBpm = ((bpm ?: 72).coerceIn(45, 160) / 6) * 6
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pulseBpm) {
        val beatMs = (60_000f / pulseBpm).toInt().coerceIn(375, 1_334)
        val systole = (beatMs * 0.18f).toInt().coerceAtLeast(70)
        val diastole = (beatMs * 0.22f).toInt().coerceAtLeast(80)
        val rest = (beatMs - systole - diastole).coerceAtLeast(40)
        while (true) {
            scale.snapTo(1f)
            scale.animateTo(1.28f, tween(systole, easing = FastOutSlowInEasing))
            scale.animateTo(1f, tween(diastole, easing = FastOutSlowInEasing))
            delay(rest.toLong())
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = "Heart rate",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer {
                    val pulse = scale.value
                    scaleX = pulse
                    scaleY = pulse
                },
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = bpm?.let { "$it bpm" } ?: "— bpm",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
}

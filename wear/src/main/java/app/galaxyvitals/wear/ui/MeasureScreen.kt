package app.galaxyvitals.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import app.galaxyvitals.wear.ui.components.EcgWaveformMini

@Composable
fun MeasureScreen(
    state: MeasureUiState,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
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
                    state.hrBpm?.let { Text("$it bpm", color = MaterialTheme.colorScheme.primary) }
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
                    state.hrBpm?.let { Text("$it bpm", color = MaterialTheme.colorScheme.primary) }
                }
                MeasurePhase.Recording -> {
                    Text(
                        "%02d".format(state.remainingSec),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    state.hrBpm?.let { Text("$it bpm", style = MaterialTheme.typography.bodySmall) }
                    EcgWaveformMini(state.liveMv, Modifier.fillMaxWidth())
                    Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancel")
                    }
                }
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

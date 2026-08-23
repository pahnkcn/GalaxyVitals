package app.galaxyvitals.wear.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppCard
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

@Composable
fun HomeScreen(
    state: HomeUiState,
    onStart: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    ScreenScaffold(scrollState = columnState) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .minimumVerticalContentPadding(ListHeaderDefaults.minimumTopListContentPadding),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Text("GalaxyVitals")
                }
            }
            item {
                Text(
                    text = state.phoneNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding),
                )
            }
            item {
                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Text("Start ECG", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item {
                AppCard(
                    onClick = onHistory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding),
                    transformation = SurfaceTransformation(transformationSpec),
                    appName = { Text("ECG") },
                    title = {
                        Text(
                            WatchSessionBpm.homeLabel(state.latest),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    time = {
                        Text(
                            if (state.count == 0) "—" else "${state.count} saved",
                            maxLines = 1,
                        )
                    },
                ) {
                    Text(
                        if (state.latest == null) {
                            "Record a 30s trace. Wrist: ${state.wrist.name.lowercase()}."
                        } else {
                            "${state.latest.durationSec.toInt()}s · ${state.latest.nLabel()}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                Button(
                    onClick = onHistory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Text("History", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item {
                Button(
                    onClick = onSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Text("Settings", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun app.galaxyvitals.data.protocol.ParsedEcgFile.nLabel(): String =
    "${samples.size} samples"

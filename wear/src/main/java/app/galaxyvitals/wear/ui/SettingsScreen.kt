package app.galaxyvitals.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import app.galaxyvitals.domain.Wrist
import app.galaxyvitals.wear.R
import app.galaxyvitals.wear.ui.theme.listSideMargin

/**
 * Which wrist, and what the sensor has to say.
 *
 * The two paragraphs at the bottom share one card. Two bare rows of prose in a
 * scrolling list on a round screen is two chances to be clipped at the edge;
 * one card is one, and the list's own scaling can shrink it as it approaches
 * the bezel.
 */
@Composable
fun SettingsScreen(
    wrist: Wrist,
    sensorNote: String,
    onWrist: (Wrist) -> Unit,
    onProbe: () -> Unit,
) {
    LaunchedEffect(Unit) { onProbe() }
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()

    ScreenScaffold(scrollState = columnState) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize().padding(horizontal = listSideMargin()),
        ) {
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
                    Text(stringResource(R.string.wear_settings))
                }
            }
            item {
                // A section label, which is what it always was.
                ListSubHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Text(stringResource(R.string.wear_wrist))
                }
            }
            item {
                RadioButton(
                    selected = wrist == Wrist.LEFT,
                    onSelect = { onWrist(Wrist.LEFT) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CHOICE_HEIGHT)
                        .transformedHeight(this, transformationSpec)
                        .minimumVerticalContentPadding(
                            ButtonDefaults.minimumVerticalListContentPadding,
                        ),
                    transformation = SurfaceTransformation(transformationSpec),
                    label = { Text(stringResource(R.string.wear_wrist_left)) },
                )
            }
            item {
                RadioButton(
                    selected = wrist == Wrist.RIGHT,
                    onSelect = { onWrist(Wrist.RIGHT) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CHOICE_HEIGHT)
                        .transformedHeight(this, transformationSpec)
                        .minimumVerticalContentPadding(
                            ButtonDefaults.minimumVerticalListContentPadding,
                        ),
                    transformation = SurfaceTransformation(transformationSpec),
                    label = { Text(stringResource(R.string.wear_wrist_right)) },
                )
            }
            item {
                Card(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .minimumVerticalContentPadding(
                            CardDefaults.minimumVerticalListContentPadding,
                        ),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = WearText.messageRes(sensorNote)
                                ?.let { stringResource(it) } ?: sensorNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.wear_disclaimer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Wear's minimum touch target, and no more than that. */
private val CHOICE_HEIGHT = 48.dp

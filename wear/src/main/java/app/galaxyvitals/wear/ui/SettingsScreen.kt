package app.galaxyvitals.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import app.galaxyvitals.domain.Wrist
import app.galaxyvitals.wear.R
import app.galaxyvitals.wear.ui.components.PlateCaption
import app.galaxyvitals.wear.ui.components.PlateRule
import app.galaxyvitals.wear.ui.components.SegmentedPlate
import app.galaxyvitals.wear.ui.components.StatusBand
import app.galaxyvitals.wear.ui.components.plate
import app.galaxyvitals.wear.ui.theme.Plate
import app.galaxyvitals.wear.ui.theme.listSideMargin
import app.galaxyvitals.wear.ui.theme.plateTaper
import app.galaxyvitals.wear.ui.theme.safeVerticalInset

/**
 * Which wrist, and what the sensor has to say.
 *
 * Left and right are two states of one setting rather than two destinations, so
 * they share a single plate split by a 1 dp rule — the opposite treatment to
 * Home's History/Settings pair, which are two places and therefore two plates
 * with ground between them. The distinction is the point: shape says what kind
 * of choice this is before the labels are read.
 *
 * The sensor note and the disclaimer are plain prose on the ground under their
 * own captions. They were a card, which made two paragraphs look like a control
 * you could press.
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
    val inset = safeVerticalInset()

    ScreenScaffold(scrollState = columnState) {
        TransformingLazyColumn(
            state = columnState,
            contentPadding = PaddingValues(vertical = inset),
            modifier = Modifier.fillMaxSize().padding(horizontal = listSideMargin()),
            verticalArrangement = Arrangement.spacedBy(PLATE_GAP),
        ) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .plateTaper(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(PLATE_GAP),
                ) {
                    StatusBand(stringResource(R.string.wear_settings))
                    PlateRule()
                }
            }
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .plateTaper(),
                    verticalArrangement = Arrangement.spacedBy(CAPTION_GAP),
                ) {
                    PlateCaption(stringResource(R.string.wear_wrist))
                    SegmentedPlate(
                        firstLabel = stringResource(R.string.wear_wrist_left),
                        firstSelected = wrist == Wrist.LEFT,
                        onFirst = { onWrist(Wrist.LEFT) },
                        secondLabel = stringResource(R.string.wear_wrist_right),
                        onSecond = { onWrist(Wrist.RIGHT) },
                    )
                }
            }
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .plateTaper(),
                    verticalArrangement = Arrangement.spacedBy(CAPTION_GAP),
                ) {
                    PlateCaption(stringResource(R.string.wear_sensor))
                    Note(WearText.messageRes(sensorNote)?.let { stringResource(it) } ?: sensorNote)
                    PlateRule(Modifier.padding(top = CAPTION_GAP), Plate.Core)
                    Note(stringResource(R.string.wear_disclaimer))
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.plate(Plate.Core),
    )
}

private val CAPTION_GAP = 6.dp

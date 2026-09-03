package app.galaxyvitals.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.galaxyvitals.R
import app.galaxyvitals.data.wear.WearLinkStatus
import app.galaxyvitals.ui.EcgScaleCalibration
import app.galaxyvitals.ui.theme.EcgType
import app.galaxyvitals.ui.theme.Mint
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    wear: WearLinkStatus,
    calibration: EcgScaleCalibration,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(20.dp))

        Section(stringResource(R.string.settings_watch_link), wear.note)
        if (wear.nodes.isNotEmpty()) {
            Section(stringResource(R.string.settings_nodes), wear.nodes.joinToString("\n"))
        }

        RulerCalibration(calibration)

        Section(
            stringResource(R.string.settings_display_title),
            stringResource(R.string.settings_display_body),
        )
        Section(
            stringResource(R.string.settings_model_title),
            stringResource(R.string.settings_model_body),
        )
        Section(
            stringResource(R.string.settings_arrival_title),
            stringResource(R.string.settings_arrival_body),
        )
        Section(
            stringResource(R.string.settings_watch_title),
            stringResource(R.string.settings_watch_body),
        )
        Section(
            stringResource(R.string.settings_import_title),
            stringResource(R.string.settings_import_body),
        )
        Section(
            stringResource(R.string.settings_disclaimer_title),
            stringResource(R.string.settings_disclaimer_body),
        )
        Section(
            stringResource(R.string.settings_license_title),
            stringResource(R.string.settings_license_body),
        )
    }
}

/**
 * Calibrates the true-scale strip against a real ruler.
 *
 * The bar is drawn 50 mm wide at the current setting, so the correction is made
 * by looking at the thing being corrected rather than at a number.
 */
@Composable
private fun RulerCalibration(calibration: EcgScaleCalibration) {
    val factor by calibration.factor.collectAsState()
    val reported = EcgScaleCalibration.reportedPxPerMm(LocalContext.current)
    val barWidth = with(LocalDensity.current) { (reported * factor * REFERENCE_MM).toDp() }

    Section(
        stringResource(R.string.calibrate_title),
        stringResource(R.string.calibrate_body),
    )
    Box(
        Modifier
            .padding(top = 12.dp)
            .width(barWidth)
            .height(10.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Mint),
    )
    Text(
        text = stringResource(R.string.calibrate_bar_label),
        style = EcgType.dataSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    Slider(
        value = factor,
        onValueChange = calibration::setFactor,
        valueRange = EcgScaleCalibration.MIN_FACTOR..EcgScaleCalibration.MAX_FACTOR,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.calibrate_summary, (factor * 100).roundToInt().toString()),
        style = EcgType.dataSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = calibration::reset) {
        Text(stringResource(R.string.calibrate_reset))
    }
}

@Composable
private fun Section(title: String, body: String) {
    Text(
        title,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
    Text(
        body,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

private const val REFERENCE_MM = 50f

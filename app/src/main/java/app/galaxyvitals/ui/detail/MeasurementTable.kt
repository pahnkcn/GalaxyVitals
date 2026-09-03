package app.galaxyvitals.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.galaxyvitals.R
import app.galaxyvitals.export.AndroidReportText
import app.galaxyvitals.export.EcgReportModel
import app.galaxyvitals.export.Measurement
import app.galaxyvitals.export.MeasurementKey
import app.galaxyvitals.export.MetricAvailability
import app.galaxyvitals.export.ReportFormat
import app.galaxyvitals.ui.theme.EcgType
import app.galaxyvitals.ui.theme.Spacing

/**
 * The clinical numbers, in the density a clinician expects, with the meaning of
 * each one a tap away for everyone else.
 *
 * Values are monospaced and right-aligned so the column reads down as a column
 * rather than as a ragged list, which is the only reason a table beats a
 * paragraph here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementTable(
    report: EcgReportModel,
    modifier: Modifier = Modifier,
) {
    var explaining: MeasurementKey? by remember { mutableStateOf(null) }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        report.measurements.forEachIndexed { index, measurement ->
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            }
            MeasurementRow(measurement) { explaining = measurement.key }
        }
    }

    explaining?.let { key ->
        ModalBottomSheet(onDismissRequest = { explaining = null }) {
            Column(
                Modifier.padding(
                    start = Spacing.page,
                    end = Spacing.page,
                    bottom = Spacing.section,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.item),
            ) {
                Text(
                    text = stringResource(AndroidReportText.measurementLabel(key)),
                    style = MaterialTheme.typography.headlineSmall,
                )
                report.measurement(key)?.let { measurement ->
                    Text(
                        text = valueText(measurement),
                        style = EcgType.dataLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = stringResource(AndroidReportText.explanation(key)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MeasurementRow(measurement: Measurement, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.card, vertical = Spacing.item),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(AndroidReportText.measurementLabel(measurement.key)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        val value = ReportFormat.value(measurement)
        if (value == null) {
            Text(
                text = stringResource(availabilityRes(measurement)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(text = value, style = EcgType.dataLarge)
            val unit = stringResource(AndroidReportText.unitLabel(measurement.key))
            if (unit.isNotEmpty()) {
                Text(
                    text = " $unit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun valueText(measurement: Measurement): String {
    val value = ReportFormat.value(measurement)
        ?: return stringResource(availabilityRes(measurement))
    val unit = stringResource(AndroidReportText.unitLabel(measurement.key))
    return if (unit.isEmpty()) value else "$value $unit"
}

/** Why the number is missing, shown in its place so the row is never a bare dash. */
private fun availabilityRes(measurement: Measurement) = when (measurement.availability) {
    MetricAvailability.INSUFFICIENT_DATA -> R.string.unavailable_insufficient_data
    MetricAvailability.LOW_QUALITY -> R.string.unavailable_low_quality
    MetricAvailability.TOO_MANY_CORRECTIONS -> R.string.unavailable_too_many_corrections
    MetricAvailability.DETECTOR_DISAGREEMENT -> R.string.unavailable_detector_disagreement
    MetricAvailability.AVAILABLE -> R.string.report_not_measured
}

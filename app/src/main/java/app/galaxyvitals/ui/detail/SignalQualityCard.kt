package app.galaxyvitals.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.galaxyvitals.R
import app.galaxyvitals.data.protocol.QualityFlag
import app.galaxyvitals.export.EcgReportModel
import app.galaxyvitals.export.ReportFormat
import app.galaxyvitals.ui.theme.Amber
import app.galaxyvitals.ui.theme.Danger
import app.galaxyvitals.ui.theme.EcgType
import app.galaxyvitals.ui.theme.Mint
import app.galaxyvitals.ui.theme.Spacing

/**
 * How much of the recording was worth reading, and what got in the way.
 *
 * Every flag is stated as something that happened to the person, not as the
 * analyser's internal name for it — "the watch lost contact with your skin"
 * tells them what to do differently next time; `CONTACT_LOSS` does not.
 */
@Composable
fun SignalQualityCard(
    report: EcgReportModel,
    modifier: Modifier = Modifier,
) {
    val quality = report.quality
    val coverage = quality.cleanCoveragePct.coerceIn(0.0, 100.0)
    val tint = when {
        coverage >= 80.0 -> Mint
        coverage >= 50.0 -> Amber
        else -> Danger
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(Spacing.card),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        Text(
            text = stringResource(R.string.section_signal_quality),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                text = ReportFormat.number(coverage, 0).orEmpty(),
                style = EcgType.dataLarge,
                color = tint,
            )
            Text(
                text = stringResource(R.string.unit_percent),
                style = MaterialTheme.typography.bodyMedium,
                color = tint,
            )
        }
        LinearProgressIndicator(
            progress = { (coverage / 100.0).toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = tint,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.quality_usable,
                ReportFormat.number(coverage, 0).orEmpty(),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (quality.noisyRangesMs.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.quality_noisy_spans,
                    quality.noisyRangesMs.joinToString(", ") { ReportFormat.spanSeconds(it) },
                ),
                style = EcgType.dataSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        quality.mainsHz?.let { hz ->
            Text(
                text = stringResource(
                    R.string.quality_mains,
                    ReportFormat.number(hz, 0).orEmpty(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        quality.flags.forEach { flag ->
            Text(
                text = "· " + stringResource(qualityFlagRes(flag)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun qualityFlagRes(flag: QualityFlag) = when (flag) {
    QualityFlag.LEGACY_TIMING -> R.string.flag_legacy_timing
    QualityFlag.UNSUPPORTED_RATE -> R.string.flag_unsupported_rate
    QualityFlag.TIMESTAMP_GAP -> R.string.flag_timestamp_gap
    QualityFlag.MISSING_SAMPLES -> R.string.flag_missing_samples
    QualityFlag.CONTACT_LOSS -> R.string.flag_contact_loss
    QualityFlag.CLIPPING -> R.string.flag_clipping
    QualityFlag.FLATLINE -> R.string.flag_flatline
    QualityFlag.HELD_SIGNAL -> R.string.flag_held_signal
    QualityFlag.IMPULSE_NOISE -> R.string.flag_impulse_noise
    QualityFlag.BASELINE_DRIFT -> R.string.flag_baseline_drift
    QualityFlag.MAINS_INTERFERENCE -> R.string.flag_mains_interference
    QualityFlag.HIGH_FREQUENCY_NOISE -> R.string.flag_high_frequency_noise
    QualityFlag.LOW_AMPLITUDE -> R.string.flag_low_amplitude
    QualityFlag.INSUFFICIENT_CLEAN_COVERAGE -> R.string.flag_insufficient_clean_coverage
}

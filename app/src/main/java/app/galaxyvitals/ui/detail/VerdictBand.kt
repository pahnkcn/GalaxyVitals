package app.galaxyvitals.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.galaxyvitals.R
import app.galaxyvitals.data.protocol.NaoLabel
import app.galaxyvitals.domain.AnalysisStatus
import app.galaxyvitals.export.AndroidReportText
import app.galaxyvitals.export.EcgReportModel
import app.galaxyvitals.export.MeasurementKey
import app.galaxyvitals.export.ReportFormat
import app.galaxyvitals.ui.theme.Amber
import app.galaxyvitals.ui.theme.Danger
import app.galaxyvitals.ui.theme.EcgType
import app.galaxyvitals.ui.theme.Mint
import app.galaxyvitals.ui.theme.Spacing

/**
 * The answer, first, in words the reader already has.
 *
 * A coloured rule down the left carries the severity so the headline itself can
 * stay plain: a red word next to a red bar shouts twice.
 */
@Composable
fun VerdictBand(
    report: EcgReportModel,
    recordedAt: String,
    modifier: Modifier = Modifier,
) {
    val verdict = report.verdict
    val tint = verdictTint(report)
    val rateText = report.measurement(MeasurementKey.HEART_RATE)?.let { ReportFormat.value(it) }

    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(tint),
        )
        Column(
            Modifier.padding(Spacing.card),
            verticalArrangement = Arrangement.spacedBy(Spacing.tight),
        ) {
            Text(
                text = stringResource(AndroidReportText.verdictTitleRes(verdict)),
                style = MaterialTheme.typography.headlineSmall,
                color = tint,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = rateText ?: "—",
                    style = EcgType.dataHero,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = " " + stringResource(R.string.unit_bpm),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            Text(
                text = recordedAt,
                style = EcgType.dataSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(AndroidReportText.verdictBodyRes(verdict)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            verdict.modelScore?.let { score ->
                Text(
                    text = stringResource(
                        R.string.verdict_model_score,
                        "${(score * 100).toInt()}%",
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun verdictTint(report: EcgReportModel): Color = when {
    report.verdict.analysisStatus != AnalysisStatus.OK -> Amber
    report.verdict.naoLabel == NaoLabel.N -> Mint
    report.verdict.naoLabel == NaoLabel.A -> Danger
    else -> Amber
}

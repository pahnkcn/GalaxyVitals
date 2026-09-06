package app.galaxyvitals.ui.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.galaxyvitals.R
import app.galaxyvitals.export.AndroidReportText
import app.galaxyvitals.export.EcgReportModel
import app.galaxyvitals.export.MeasurementKey
import app.galaxyvitals.export.ReportFormat
import app.galaxyvitals.ui.theme.EcgType
import app.galaxyvitals.ui.theme.Spacing
import app.galaxyvitals.ui.theme.labelStyle
import app.galaxyvitals.ui.theme.WASH_MS
import app.galaxyvitals.ui.theme.mm
import app.galaxyvitals.ui.theme.verdictColor

/**
 * The answer, first, in words the reader already has.
 *
 * A coloured rule down the left carries the severity so the headline can stay
 * plain — and it is the only colour on the screen, which is what makes it worth
 * reading. The rule washes in rather than appearing, because the result arrives
 * after the recording does.
 */
@Composable
fun VerdictBand(
    report: EcgReportModel,
    recordedAt: String,
    modifier: Modifier = Modifier,
) {
    val verdict = report.verdict
    val target = verdictColor(verdict.analysisStatus, verdict.naoLabel)
    val tint by animateColorAsState(
        targetValue = target,
        animationSpec = tween(WASH_MS),
        label = "verdict",
    )
    val rateText = report.measurement(MeasurementKey.HEART_RATE)?.let { ReportFormat.value(it) }

    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(0.35f.mm)
                .fillMaxHeight()
                .background(tint),
        )
        Column(
            Modifier.padding(start = Spacing.item, end = Spacing.page, bottom = Spacing.item),
            verticalArrangement = Arrangement.spacedBy(Spacing.hair),
        ) {
            Text(
                text = stringResource(AndroidReportText.verdictTitleRes(verdict)),
                style = MaterialTheme.typography.headlineSmall,
                color = tint,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = rateText ?: "—",
                    style = EcgType.dataDisplay,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.size(Spacing.tight))
                Text(
                    text = stringResource(R.string.unit_bpm).uppercase(),
                    style = labelStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Spacing.tight),
                )
            }
            Text(
                text = recordedAt,
                style = EcgType.dataSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(Spacing.hair))
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
                    style = EcgType.dataSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

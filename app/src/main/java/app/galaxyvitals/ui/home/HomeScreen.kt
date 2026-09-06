package app.galaxyvitals.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import app.galaxyvitals.R
import app.galaxyvitals.data.wear.WearLinkStatus
import app.galaxyvitals.domain.EcgSession
import app.galaxyvitals.domain.EcgSource
import app.galaxyvitals.domain.Wrist
import app.galaxyvitals.ui.HomeUiState
import app.galaxyvitals.ui.components.EcgPreviewStrip
import app.galaxyvitals.ui.dayLabel
import app.galaxyvitals.ui.displayBpm
import app.galaxyvitals.ui.durationLabel
import app.galaxyvitals.ui.hrLabel
import app.galaxyvitals.ui.naoLabelOrNull
import app.galaxyvitals.ui.naoTitleRes
import app.galaxyvitals.ui.theme.EcgType
import app.galaxyvitals.ui.theme.HealthTrackTheme
import app.galaxyvitals.ui.theme.Spacing
import app.galaxyvitals.ui.theme.labelStyle
import app.galaxyvitals.ui.theme.mm
import app.galaxyvitals.ui.theme.rememberBeatPulse
import app.galaxyvitals.ui.theme.rememberSweep
import app.galaxyvitals.ui.theme.verdictColor
import app.galaxyvitals.ui.timeLabel
import kotlin.math.roundToInt

/**
 * One screen, one answer.
 *
 * The verdict, the rate and the shape of the last few beats sit above the fold
 * and everything else is a hairline row, because the question a person opens
 * this app with has exactly one answer and it should not have to be found.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenEcg: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onImport: () -> Unit,
    onSync: () -> Unit,
    onOpenBp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        AppHead(state.wear, state.latest?.displayBpm(), onSync)
        Answer(
            session = state.latest,
            preview = state.preview,
            onOpen = { state.latest?.let { onOpenEcg(it.sessionId) } },
        )

        Spacer(Modifier.height(Spacing.page))
        OutlinedButton(
            onClick = onImport,
            enabled = !state.busy,
            shape = RoundedCornerShape(Spacing.item),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.page)
                .height(Spacing.box + Spacing.item),
        ) {
            Text(
                stringResource(if (state.busy) R.string.home_working else R.string.action_import),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(Spacing.item))

        QuietRow(
            text = stringResource(R.string.home_recordings, state.count),
            trailing = stringResource(R.string.action_history),
            onClick = onOpenHistory,
        )
        QuietRow(
            text = stringResource(R.string.home_bp_line),
            trailing = stringResource(R.string.home_bp_soon),
            onClick = onOpenBp,
        )
        Spacer(Modifier.height(Spacing.section))
    }
}

/** The wordmark, and whether the watch is there. Nothing else earns the row. */
@Composable
private fun AppHead(status: WearLinkStatus, bpm: Double?, onSync: () -> Unit) {
    val pulse by rememberBeatPulse(bpm.takeIf { status.available })
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSync)
            .padding(horizontal = Spacing.page, vertical = Spacing.item),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.app_name).uppercase(),
            style = labelStyle(),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(1.1f.mm)
                .scale(pulse)
                .clip(CircleShape)
                .background(
                    if (status.available) {
                        MaterialTheme.colorScheme.onBackground
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
        )
        Spacer(Modifier.size(Spacing.tight))
        Text(
            stringResource(
                if (status.available) R.string.home_watch_linked else R.string.home_watch_not_linked,
            ).uppercase(),
            style = labelStyle(),
        )
    }
}

@Composable
private fun Answer(session: EcgSession?, preview: List<Float>, onOpen: () -> Unit) {
    if (session == null) {
        Column(
            Modifier.padding(horizontal = Spacing.page, vertical = Spacing.item),
            verticalArrangement = Arrangement.spacedBy(Spacing.tight),
        ) {
            Text(
                stringResource(R.string.home_no_recordings),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.home_no_recordings_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val tint = verdictColor(session.analysisStatus, session.naoLabelOrNull())
    val open = stringResource(R.string.home_open_latest)
    val sweep = rememberSweep(session.sessionId)

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .semantics { contentDescription = open },
    ) {
        Column(Modifier.padding(horizontal = Spacing.page)) {
            Text(
                text = stringResource(
                    R.string.history_row_summary,
                    session.dayLabel(),
                    session.timeLabel(),
                    session.durationLabel(),
                ),
                style = EcgType.dataSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.item))
            Text(
                text = stringResource(session.naoTitleRes()),
                style = MaterialTheme.typography.headlineSmall,
                color = tint,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(session.hrLabel(), style = EcgType.dataHero)
                Spacer(Modifier.size(Spacing.tight))
                Text(
                    stringResource(R.string.unit_bpm).uppercase(),
                    style = labelStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Spacing.item),
                )
            }
        }
        Spacer(Modifier.height(Spacing.item))
        EcgPreviewStrip(values = preview, sweep = sweep)
        Text(
            stringResource(R.string.home_strip_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.page, vertical = Spacing.tight),
        )
        Spacer(Modifier.height(Spacing.tight))
        Facts(session)
    }
}

/** What this recording measured, in the order a reader asks for it. */
@Composable
private fun Facts(session: EcgSession) {
    val range = if (session.hrMin != null && session.hrMax != null) {
        "${session.hrMin}–${session.hrMax}"
    } else {
        null
    }
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Fact(stringResource(R.string.measure_heart_rate_range), range, R.string.unit_bpm)
        Fact(
            stringResource(R.string.report_field_clean_coverage),
            session.usablePct.takeIf { it.isFinite() }?.roundToInt()?.toString(),
            R.string.unit_percent,
        )
        Fact(
            stringResource(R.string.report_field_duration),
            session.durationSec.takeIf { it.isFinite() }?.roundToInt()?.toString(),
            R.string.unit_seconds,
        )
    }
}

@Composable
private fun Fact(label: String, value: String?, unitRes: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.page, vertical = Spacing.tight + Spacing.hair),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value ?: "—", style = EcgType.dataMedium)
        Spacer(Modifier.size(Spacing.hair))
        Text(
            stringResource(unitRes).uppercase(),
            style = labelStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun QuietRow(text: String, trailing: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.page, vertical = Spacing.item),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(trailing.uppercase(), style = EcgType.annotation)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Preview(showBackground = true, backgroundColor = 0xFF141110)
@Composable
private fun HomeScreenPreview() {
    HealthTrackTheme(darkTheme = true) {
        HomeScreen(
            state = HomeUiState(
                latest = EcgSession(
                    sessionId = "preview",
                    filePath = "",
                    tsStartMs = System.currentTimeMillis(),
                    srHz = 500,
                    nSamples = 15000,
                    durationSec = 30.0,
                    hrMedian = 68.0,
                    hrMin = 61,
                    hrMax = 74,
                    hrCoveragePct = 90.0,
                    usablePct = 97.0,
                    wrist = Wrist.LEFT,
                    signFactor = 1,
                    polarityNormalized = true,
                    unit = "mV",
                    watchInfo = "",
                    source = EcgSource.IMPORT,
                    createdAtMs = System.currentTimeMillis(),
                ),
                count = 12,
                wear = WearLinkStatus(true, emptyList(), ""),
            ),
            onOpenEcg = {},
            onOpenHistory = {},
            onImport = {},
            onSync = {},
            onOpenBp = {},
        )
    }
}

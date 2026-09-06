package app.galaxyvitals.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import app.galaxyvitals.R
import app.galaxyvitals.domain.EcgSession
import app.galaxyvitals.domain.EcgSource
import app.galaxyvitals.domain.Wrist
import app.galaxyvitals.ui.components.EcgRowSparkline
import app.galaxyvitals.ui.dayLabel
import app.galaxyvitals.ui.hrLabel
import app.galaxyvitals.ui.naoLabelOrNull
import app.galaxyvitals.ui.theme.EcgType
import app.galaxyvitals.ui.theme.HealthTrackTheme
import app.galaxyvitals.ui.theme.Spacing
import app.galaxyvitals.ui.theme.labelStyle
import app.galaxyvitals.ui.theme.mm
import app.galaxyvitals.ui.theme.verdictColor
import app.galaxyvitals.ui.timeLabel

/**
 * A run of recordings, scannable for shape as well as for rate.
 *
 * Each row carries its own trace, so a change in the way the beats sit is
 * visible without opening anything; the rule at the left edge is the verdict,
 * which is the only colour in the list.
 */
@Composable
fun HistoryScreen(
    sessions: List<EcgSession>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    previews: Map<String, List<Float>> = emptyMap(),
    onRequestPreview: (String) -> Unit = {},
    watchLinked: Boolean = false,
    onClearWatchHistory: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.page, vertical = Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.history_title).uppercase(),
                style = labelStyle(),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.home_recordings, sessions.size).uppercase(),
                style = labelStyle(),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        if (sessions.isEmpty()) {
            Column(
                Modifier.padding(Spacing.page),
                verticalArrangement = Arrangement.spacedBy(Spacing.tight),
            ) {
                Text(
                    stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (watchLinked && onClearWatchHistory != null) {
                    TextButton(
                        onClick = onClearWatchHistory,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(stringResource(R.string.history_clear_watch))
                    }
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = Spacing.section)) {
                items(sessions, key = { it.sessionId }) { session ->
                    HistoryRow(
                        session = session,
                        preview = previews[session.sessionId],
                        onRequestPreview = onRequestPreview,
                        onClick = { onOpen(session.sessionId) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    session: EcgSession,
    preview: List<Float>?,
    onRequestPreview: (String) -> Unit,
    onClick: () -> Unit,
) {
    LaunchedEffect(session.sessionId) { onRequestPreview(session.sessionId) }
    val tint = verdictColor(session.analysisStatus, session.naoLabelOrNull())

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.page, vertical = Spacing.item),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        Box(
            Modifier
                .width(0.35f.mm)
                .height(Spacing.box)
                .background(tint),
        )
        Column(Modifier.width(9f.mm)) {
            Text(session.timeLabel(), style = EcgType.dataMedium)
            Text(
                session.dayLabel().uppercase(),
                style = labelStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        EcgRowSparkline(
            values = preview.orEmpty(),
            modifier = Modifier
                .weight(1f)
                .height(Spacing.box),
        )
        Text(session.hrLabel(), style = EcgType.dataLarge)
        Text(
            stringResource(R.string.unit_bpm).uppercase(),
            style = labelStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF141110)
@Composable
private fun HistoryScreenPreview() {
    HealthTrackTheme(darkTheme = true) {
        HistoryScreen(
            sessions = listOf(
                EcgSession(
                    sessionId = "a",
                    filePath = "",
                    tsStartMs = System.currentTimeMillis(),
                    srHz = 500,
                    nSamples = 1000,
                    durationSec = 30.0,
                    hrMedian = 72.0,
                    hrMin = 60,
                    hrMax = 80,
                    hrCoveragePct = 80.0,
                    usablePct = 95.0,
                    wrist = Wrist.LEFT,
                    signFactor = 1,
                    polarityNormalized = true,
                    unit = "mV",
                    watchInfo = "",
                    source = EcgSource.IMPORT,
                    createdAtMs = 0L,
                ),
            ),
            onOpen = {},
        )
    }
}

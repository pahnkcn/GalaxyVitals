package app.galaxyvitals.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.galaxyvitals.domain.EcgSession
import app.galaxyvitals.domain.EcgSource
import app.galaxyvitals.domain.Wrist
import app.galaxyvitals.ui.dayLabel
import app.galaxyvitals.ui.durationLabel
import app.galaxyvitals.ui.hrLabel
import app.galaxyvitals.ui.naoTitle
import app.galaxyvitals.ui.theme.HealthTrackTheme
import app.galaxyvitals.ui.theme.Mint
import app.galaxyvitals.ui.timeLabel

@Composable
fun HistoryScreen(
    sessions: List<EcgSession>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text(
            "History",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        )
        if (sessions.isEmpty()) {
            Text(
                "Imported and received recordings will land here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sessions, key = { it.sessionId }) { session ->
                    HistoryRow(session) { onOpen(session.sessionId) }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(session: EcgSession, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(session.dayLabel(), fontWeight = FontWeight.Medium)
            Text(
                "${session.timeLabel()}  ·  ${session.durationLabel()}  ·  ${session.naoTitle()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(session.hrLabel(), fontSize = 28.sp, color = Mint, fontWeight = FontWeight.Light)
        Text(" bpm", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF071016)
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

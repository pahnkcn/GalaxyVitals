package app.healthtrack.ui.home

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.healthtrack.data.wear.WearLinkStatus
import app.healthtrack.domain.EcgSession
import app.healthtrack.domain.EcgSource
import app.healthtrack.domain.Wrist
import app.healthtrack.ui.HomeUiState
import app.healthtrack.ui.durationLabel
import app.healthtrack.ui.hrLabel
import app.healthtrack.ui.naoTitle
import app.healthtrack.ui.stampLabel
import app.healthtrack.ui.theme.HealthTrackTheme
import app.healthtrack.ui.theme.InkHigh
import app.healthtrack.ui.theme.Mint

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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Today", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "ECG from your watch, kept on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        WatchChip(state.wear, onSync)

        EcgHeroCard(
            session = state.latest,
            count = state.count,
            onOpen = { state.latest?.let { onOpenEcg(it.sessionId) } },
            onHistory = onOpenHistory,
        )

        Button(
            onClick = onImport,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(if (state.busy) "Working…" else "Import recording")
        }
        BloodPressureStub(onOpen = onOpenBp)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WatchChip(status: WearLinkStatus, onSync: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (status.available) Mint.copy(alpha = 0.2f) else InkHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Watch, contentDescription = null, tint = if (status.available) Mint else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(
                if (status.available) "Watch linked" else "Watch not linked",
                fontWeight = FontWeight.Medium,
            )
            Text(
                status.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onSync, shape = RoundedCornerShape(12.dp)) {
            Text("Sync")
        }
    }
}

@Composable
private fun EcgHeroCard(
    session: EcgSession?,
    count: Int,
    onOpen: () -> Unit,
    onHistory: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = session != null, onClick = onOpen)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.MonitorHeart, contentDescription = null, tint = Mint)
            Text("Electrocardiogram", fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(18.dp))
        if (session == null) {
            Text("No recordings yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Record on the GalaxyBridge watch app, or import a csv.gz.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(session.hrLabel(), fontSize = 64.sp, fontWeight = FontWeight.Light, color = Mint, lineHeight = 64.sp)
                Text(
                    "  bpm",
                    modifier = Modifier.padding(bottom = 10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${session.naoTitle()}  ·  ${session.stampLabel()}  ·  ${session.durationLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onHistory, shape = RoundedCornerShape(12.dp)) {
            Text(if (count == 0) "History" else "$count in history")
        }
    }
}

@Composable
private fun BloodPressureStub(onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.FavoriteBorder, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        Column(Modifier.weight(1f)) {
            Text("Blood pressure", fontWeight = FontWeight.Medium)
            Text("Reserved for a later source", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Soon", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Medium)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF071016)
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
                count = 3,
                wear = WearLinkStatus(false, emptyList(), "No Wear OS node for this package."),
            ),
            onOpenEcg = {},
            onOpenHistory = {},
            onImport = {},
            onSync = {},
            onOpenBp = {},
        )
    }
}

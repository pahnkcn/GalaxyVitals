package app.healthtrack.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.healthtrack.analysis.shortHelp
import app.healthtrack.data.protocol.NaoLabel
import app.healthtrack.domain.AnalysisStatus
import app.healthtrack.domain.EcgSample
import app.healthtrack.domain.EcgSession
import app.healthtrack.ui.components.EcgWaveform
import app.healthtrack.ui.durationLabel
import app.healthtrack.ui.findingRows
import app.healthtrack.ui.hrLabel
import app.healthtrack.ui.naoConfidenceLabel
import app.healthtrack.ui.naoTitle
import app.healthtrack.ui.stampLabel
import app.healthtrack.ui.theme.Amber
import app.healthtrack.ui.theme.Danger
import app.healthtrack.ui.theme.Mint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EcgDetailScreen(
    session: EcgSession?,
    samples: List<EcgSample>,
    onLoad: (String) -> Unit,
    onBack: () -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(session?.sessionId) {
        session?.sessionId?.let(onLoad)
    }
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("ECG") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (session != null) {
                    TextButton(onClick = {
                        onDelete(session.sessionId)
                        onBack()
                    }) { Text("Delete") }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        if (session == null) {
            Text("Recording missing", modifier = Modifier.padding(20.dp))
            return
        }
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(session.hrLabel(), fontSize = 72.sp, fontWeight = FontWeight.Light, color = Mint)
            Text("median bpm", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(session.stampLabel(), modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
            AnalysisCard(session)
            Spacer(Modifier.height(16.dp))
            EcgWaveform(
                samples = samples,
                interactive = true,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp),
            )
            Text(
                "Pinch to zoom · drag to pan · ${session.srHz} Hz · ${session.unit}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatChip("Min", session.hrMin?.toString() ?: "—", Modifier.weight(1f))
                StatChip("Max", session.hrMax?.toString() ?: "—", Modifier.weight(1f))
                StatChip("Time", session.durationLabel(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatChip("Wrist", session.wrist.name.lowercase(), Modifier.weight(1f))
                StatChip("Usable", "${session.usablePct.toInt()}%", Modifier.weight(1f))
                StatChip("HR cover", "${session.hrCoveragePct.toInt()}%", Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Screening only. ECGFounder is an open model, not a medical device. " +
                    "A single-lead watch strip cannot replace a 12-lead ECG or a clinician.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AnalysisCard(session: EcgSession) {
    val nao = session.naoLabel?.let { runCatching { NaoLabel.valueOf(it) }.getOrNull() }
    val tint = when (nao) {
        NaoLabel.A -> Danger
        NaoLabel.O -> Amber
        else -> Mint
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Rhythm screen", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                session.naoTitle(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
            if (session.naoConfidence != null) {
                Text(
                    session.naoConfidenceLabel(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        val body = when (session.analysisStatus) {
            AnalysisStatus.PENDING -> "Running ECGFounder on this recording…"
            AnalysisStatus.FAILED -> session.analysisNote.ifBlank { "Analysis failed." }
            AnalysisStatus.LOW_QUALITY ->
                (nao?.shortHelp() ?: "Low-quality strip.") + " ${session.analysisNote}"
            AnalysisStatus.OK -> nao?.shortHelp() ?: "Analysis complete."
            AnalysisStatus.NONE -> "No analysis yet."
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        session.findingRows().forEach { (name, score) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(score, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (session.analysisNote.isNotBlank() && session.analysisStatus == AnalysisStatus.OK) {
            Text(session.analysisNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
    }
}

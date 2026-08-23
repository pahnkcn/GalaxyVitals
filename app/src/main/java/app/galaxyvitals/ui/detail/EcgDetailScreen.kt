package app.galaxyvitals.ui.detail

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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.galaxyvitals.analysis.shortHelp
import app.galaxyvitals.analysis.EcgAnalysisBundle
import app.galaxyvitals.data.protocol.NaoLabel
import app.galaxyvitals.domain.AnalysisStatus
import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.domain.EcgSession
import app.galaxyvitals.ui.components.EcgWaveform
import app.galaxyvitals.ui.durationLabel
import app.galaxyvitals.ui.findingRows
import app.galaxyvitals.ui.hrLabel
import app.galaxyvitals.ui.hrSourceLabel
import app.galaxyvitals.ui.naoConfidenceLabel
import app.galaxyvitals.ui.naoTitle
import app.galaxyvitals.ui.stampLabel
import app.galaxyvitals.ui.theme.Amber
import app.galaxyvitals.ui.theme.Danger
import app.galaxyvitals.ui.theme.Mint

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
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(session?.sessionId) {
        session?.sessionId?.let(onLoad)
    }
    if (confirmDelete && session != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete recording?") },
            text = { Text("This removes the recording from this phone and the linked watch. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(session.sessionId)
                    confirmDelete = false
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
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
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        if (session == null) {
            Text("Recording missing", modifier = Modifier.padding(20.dp))
        } else {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(session.hrLabel(), fontSize = 72.sp, fontWeight = FontWeight.Light, color = Mint)
            Text(session.hrSourceLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(session.stampLabel(), modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
            AnalysisCard(session)
            if (session.analysisBundleId != null &&
                session.analysisBundleId != EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID
            ) {
                Text(
                    "This analysis is stale because the verified analysis bundle changed.",
                    color = Amber,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
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
                "Drag to pan · use + / – to zoom · ${session.srHz} Hz · ${session.unit}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Display filtered 0.5–40 Hz · stored ECG remains raw",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatChip("Clean", "${session.cleanCoveragePct.toInt()}%", Modifier.weight(1f))
                StatChip("Timing", session.timingTrust.lowercase(), Modifier.weight(1f))
                StatChip("Time", session.durationLabel(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatChip("Wrist", session.wrist.name.lowercase(), Modifier.weight(1f))
                StatChip("Quality", session.qualityStatus.lowercase(), Modifier.weight(1f))
                StatChip("Schema", "v${session.inputSchemaVersion}", Modifier.weight(1f))
            }
            if (session.qualityFlagsJson != "[]") {
                Text(
                    "Quality flags: ${session.qualityFlagsJson}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Screening only. The on-device N/A/O rhythm model is not a medical device. " +
                    "A single-lead watch strip cannot replace a 12-lead ECG or a clinician.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
        }
        }
    }
}

@Composable
private fun AnalysisCard(session: EcgSession) {
    val nao = session.naoLabel?.let { runCatching { NaoLabel.valueOf(it) }.getOrNull() }
    val tint = when {
        session.analysisStatus == AnalysisStatus.FAILED -> Danger
        session.analysisStatus == AnalysisStatus.LOW_QUALITY -> Amber
        session.analysisStatus == AnalysisStatus.INDETERMINATE -> Amber
        nao == NaoLabel.A -> Danger
        nao == NaoLabel.O -> Amber
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
            val confidence = session.naoConfidenceLabel()
            if (confidence.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    Text("model score", style = MaterialTheme.typography.labelSmall)
                    Text(confidence, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        val body = when (session.analysisStatus) {
            AnalysisStatus.PENDING -> "Running the on-device N/A/O rhythm model on this recording…"
            AnalysisStatus.FAILED -> session.analysisNote.ifBlank { "Analysis failed." }
            AnalysisStatus.LOW_QUALITY -> session.analysisNote.ifBlank {
                "The strip quality is too low for a rhythm result."
            }
            AnalysisStatus.INDETERMINATE -> session.analysisNote.ifBlank { "No rhythm result is available." }
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

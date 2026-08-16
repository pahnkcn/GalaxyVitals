package app.healthtrack.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.healthtrack.data.wear.WearLinkStatus

@Composable
fun SettingsScreen(
    wear: WearLinkStatus,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(20.dp))
        Section("Watch link", wear.note)
        if (wear.nodes.isNotEmpty()) {
            Section("Nodes", wear.nodes.joinToString("\n"))
        }
        Section(
            "How ECG arrives",
            "The HealthTrack watch app writes gzip CSV and pushes DataItem /ecg/session/{id}. " +
                "This phone listens on that path. Google only delivers those items when both " +
                "apps share application id app.healthtrack and the same signing key.",
        )
        Section(
            "Install the watch app",
            "Sideload the :wear debug APK onto a paired Wear OS watch. Home → Sync asks the " +
                "watch to re-push any leftover inbox files.",
        )
        Section(
            "Import",
            "You can still import ecg_*.csv.gz from another source on Home.",
        )
        Section(
            "Disclaimer",
            "GalaxyBridge is not a medical device and does not diagnose conditions. " +
                "If you feel unwell, seek professional care.",
        )
        Section("License", "Apache License 2.0 · application id app.healthtrack")
    }
}

@Composable
private fun Section(title: String, body: String) {
    Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
    Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

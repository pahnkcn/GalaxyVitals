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
            "The watch writes gzip CSV and pushes DataItem /ecg/session/{id} with an Asset. " +
                "This phone listens on that path. Google only delivers those items to an app " +
                "with the same application id and signing key as the watch app.",
        )
        Section(
            "Import",
            "If the watch app is a different package, copy ecg_*.csv.gz off the watch " +
                "(or from another companion) and use Import on Home.",
        )
        Section(
            "Disclaimer",
            "HealthTrack is not a medical device and does not diagnose conditions. " +
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

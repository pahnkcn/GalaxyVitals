package app.galaxyvitals.ui.settings

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
import app.galaxyvitals.data.wear.WearLinkStatus

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
            "The GalaxyVitals watch app writes gzip CSV and pushes DataItem /ecg/session/{id}. " +
                "This phone listens on that path. Google only delivers those items when both " +
                "apps share application id app.galaxyvitals and the same signing key.",
        )
        Section(
            "Galaxy Watch",
            "Sideload the GalaxyVitals watch APK on a paired Galaxy Watch. Hardware " +
                "ECG_ON_DEMAND needs Samsung’s official sensor AAR and a partner " +
                "whitelist for app.galaxyvitals. A different vendor companion cannot talk to this " +
                "phone app.",
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
            "Rhythm model",
            "After each recording the phone runs ECGFounder 1-lead (MIT, 2025) locally. " +
                "A small calibrated head reads its 150 findings as N / A / O: normal, " +
                "atrial fibrillation, or other. Galaxy Watch strips are 500 Hz lead-I-like. " +
                "This is a screen, not a diagnosis.",
        )
        Section(
            "Disclaimer",
            "GalaxyVitals is not a medical device and does not diagnose conditions. " +
                "If you feel unwell, seek professional care.",
        )
        Section("License", "Apache License 2.0 · ECGFounder MIT · application id app.galaxyvitals")
    }
}

@Composable
private fun Section(title: String, body: String) {
    Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
    Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

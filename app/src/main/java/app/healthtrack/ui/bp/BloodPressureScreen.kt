package app.healthtrack.ui.bp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.healthtrack.domain.VitalType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BloodPressureScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        TopAppBar(
            title = { Text("Blood pressure") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Text(
            "This slot is reserved as ${VitalType.BLOOD_PRESSURE}. " +
                "A later source can implement the same VitalType without changing ECG storage.",
            modifier = Modifier.padding(20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

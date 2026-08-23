package app.galaxyvitals.ui.bp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.galaxyvitals.domain.VitalType
import app.galaxyvitals.ui.components.ScreenTopBar

@Composable
fun BloodPressureScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ScreenTopBar(title = "Blood pressure", onBack = onBack)
        Text(
            "This slot is reserved as ${VitalType.BLOOD_PRESSURE}. " +
                "A later source can implement the same VitalType without changing ECG storage.",
            modifier = Modifier.padding(20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

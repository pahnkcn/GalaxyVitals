package app.galaxyvitals.wear.ui

import androidx.compose.runtime.Composable
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.ui.tooling.preview.WearPreviewFontScales
import app.galaxyvitals.wear.ui.theme.HealthTrackWearTheme

@WearPreviewDevices
@WearPreviewFontScales
@Composable
private fun MeasureScreenWaitingForContactPreview() {
    HealthTrackWearTheme {
        MeasureScreen(
            state = MeasureUiState(
                phase = MeasurePhase.WaitingForContact,
                status = "Touch the sensor to begin",
                remainingSec = 0,
                samsungReady = true,
            ),
            onRetry = {},
            onDone = {},
        )
    }
}

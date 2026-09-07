package app.galaxyvitals.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.ui.tooling.preview.WearPreviewFontScales
import androidx.wear.tooling.preview.devices.WearDevices
import app.galaxyvitals.domain.Wrist
import app.galaxyvitals.wear.ui.theme.HealthTrackWearTheme

/**
 * The three faces this app actually ships to.
 *
 * The plate grid is expressed as fractions of the face, so the only way to know
 * it holds is to render the same screen at every diameter it will meet. The
 * base is the 44 mm Watch 9 — the device PROTOCOL.md validates against — with
 * the 40 mm below it and the Ultra 2 above.
 *
 * Densities are the real ones: all three panels are xhdpi, so the pixel counts
 * divide by two to give the dp faces named here.
 */
@Preview(name = "Watch 9 40 mm", device = "spec:width=219dp,height=219dp,dpi=320,isRound=true", showSystemUi = true, group = "faces")
@Preview(name = "Watch 9 44 mm", device = "spec:width=240dp,height=240dp,dpi=320,isRound=true", showSystemUi = true, group = "faces")
@Preview(name = "Watch Ultra 2", device = "spec:width=249dp,height=249dp,dpi=320,isRound=true", showSystemUi = true, group = "faces")
annotation class GalaxyWatchPreviews

/**
 * Round-screen previews.
 *
 * `@WearPreviewDevices` covers small and large round plus square, and
 * `@WearPreviewFontScales` covers the accessibility sizes — between them they
 * catch the clipping this redesign exists to fix. Neither carries a locale, so
 * [ThaiWearPreviews] adds the Thai pass separately, and [GalaxyWatchPreviews]
 * pins the three real faces.
 */
@Preview(device = WearDevices.SMALL_ROUND, locale = "th", showSystemUi = true, group = "th")
@Preview(device = WearDevices.LARGE_ROUND, locale = "th", showSystemUi = true, group = "th")
annotation class ThaiWearPreviews

@WearPreviewDevices
@WearPreviewFontScales
@ThaiWearPreviews
@GalaxyWatchPreviews
@Composable
private fun HomeEmptyPreview() {
    HealthTrackWearTheme {
        HomeScreen(
            state = HomeUiState(
                latest = null,
                count = 0,
                phones = emptyList(),
                wrist = Wrist.LEFT,
                checkingPhone = false,
            ),
            onStart = {},
            onHistory = {},
            onSettings = {},
            onRefresh = {},
        )
    }
}

@WearPreviewDevices
@WearPreviewFontScales
@ThaiWearPreviews
@GalaxyWatchPreviews
@Composable
private fun MeasureArmedCountdownPreview() {
    HealthTrackWearTheme {
        MeasureScreen(
            state = MeasureUiState(
                phase = MeasurePhase.ArmedCountdown,
                status = "Starting in",
                remainingSec = 3,
                samsungReady = true,
            ),
            onRetry = {},
            onDone = {},
        )
    }
}

@WearPreviewDevices
@WearPreviewFontScales
@ThaiWearPreviews
@GalaxyWatchPreviews
@Composable
private fun MeasureRecordingPreview() {
    HealthTrackWearTheme {
        MeasureScreen(
            state = MeasureUiState(
                phase = MeasurePhase.Recording,
                status = "Recording",
                remainingSec = 18,
                samsungReady = true,
            ),
            onRetry = {},
            onDone = {},
        )
    }
}

/** The longest Thai message the app can show, against a pinned action plate. */
@WearPreviewDevices
@WearPreviewFontScales
@ThaiWearPreviews
@GalaxyWatchPreviews
@Composable
private fun MeasureFailedPreview() {
    HealthTrackWearTheme {
        MeasureScreen(
            state = MeasureUiState(
                phase = MeasurePhase.Failed,
                status = "Recording failed",
                remainingSec = 0,
                error = "Wear the watch and keep a finger on the top sensor, then try again.",
            ),
            onRetry = {},
            onDone = {},
        )
    }
}

/** Full rail, hero rate, and the longest sync status in the band. */
@WearPreviewDevices
@WearPreviewFontScales
@ThaiWearPreviews
@GalaxyWatchPreviews
@Composable
private fun MeasureSuccessPreview() {
    HealthTrackWearTheme {
        MeasureScreen(
            state = MeasureUiState(
                phase = MeasurePhase.Success,
                status = "ACKNOWLEDGED",
                remainingSec = 0,
            ),
            onRetry = {},
            onDone = {},
        )
    }
}

/** A Thai and Latin label in one action plate. */
@WearPreviewDevices
@ThaiWearPreviews
@Composable
private fun MeasurePermissionRequiredPreview() {
    HealthTrackWearTheme {
        MeasureScreen(
            state = MeasureUiState(
                phase = MeasurePhase.PermissionRequired,
                status = "Sensor permissions needed",
                remainingSec = 0,
                error = "Samsung ECG and heart-rate permissions are required.",
            ),
            onRetry = {},
            onDone = {},
        )
    }
}

@WearPreviewDevices
@WearPreviewFontScales
@ThaiWearPreviews
@GalaxyWatchPreviews
@Composable
private fun HistoryEmptyPreview() {
    HealthTrackWearTheme { HistoryScreen(sessions = emptyList(), onRefresh = {}) }
}

@WearPreviewDevices
@WearPreviewFontScales
@ThaiWearPreviews
@GalaxyWatchPreviews
@Composable
private fun SettingsPreview() {
    HealthTrackWearTheme {
        SettingsScreen(
            wrist = Wrist.LEFT,
            sensorNote = "Samsung ECG tracker ready",
            onWrist = {},
            onProbe = {},
        )
    }
}

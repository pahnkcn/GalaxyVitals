package app.galaxyvitals.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.ui.tooling.preview.WearPreviewFontScales
import androidx.wear.tooling.preview.devices.WearDevices
import app.galaxyvitals.domain.Wrist
import app.galaxyvitals.wear.ui.theme.BottomArc
import app.galaxyvitals.wear.ui.theme.HealthTrackWearTheme
import app.galaxyvitals.wear.ui.theme.TopArc

/**
 * Round-screen previews.
 *
 * `@WearPreviewDevices` covers small and large round plus square, and
 * `@WearPreviewFontScales` covers the accessibility sizes — between them they
 * catch the clipping this redesign exists to fix. Neither carries a locale, so
 * [ThaiWearPreviews] adds the Thai pass separately.
 */
@Preview(device = WearDevices.SMALL_ROUND, locale = "th", showSystemUi = true, group = "th")
@Preview(device = WearDevices.LARGE_ROUND, locale = "th", showSystemUi = true, group = "th")
annotation class ThaiWearPreviews

@WearPreviewDevices
@WearPreviewFontScales
@ThaiWearPreviews
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

/** The longest Thai message the app can show, against an EdgeButton. */
@WearPreviewDevices
@WearPreviewFontScales
@ThaiWearPreviews
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

/** Closed rim, hero rate, and the longest sync status on the top arc. */
@WearPreviewDevices
@WearPreviewFontScales
@ThaiWearPreviews
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

/** A Thai and Latin label in one EdgeButton. */
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
@Composable
private fun HistoryEmptyPreview() {
    HealthTrackWearTheme { HistoryScreen(sessions = emptyList(), onRefresh = {}) }
}

@WearPreviewDevices
@WearPreviewFontScales
@ThaiWearPreviews
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

/**
 * The canary.
 *
 * Four Thai strings on the arcs, each stressing a different feature of the
 * script: mark stacking, the leading vowels that need visual reordering before
 * shaping, a two-level stack with a Latin break, and mixed Thai and Arabic
 * numerals. If any of these come out with marks clipped, leading vowels after
 * their consonant, or a base glyph split from its mark, set
 * `ArcText.CURVED_THAI = false` and every arc in the app falls back to straight
 * text without a screen being touched.
 */
@ThaiWearPreviews
@WearPreviewFontScales
@Composable
private fun ArcTextThaiPreview() {
    HealthTrackWearTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TopArc("กำลังบันทึก · โทรศัพท์ยืนยันรับแล้ว", MaterialTheme.typography.arcMedium)
            BottomArc("ครั้ง/นาที · เหลือ 12 วิ", MaterialTheme.typography.arcSmall)
        }
    }
}

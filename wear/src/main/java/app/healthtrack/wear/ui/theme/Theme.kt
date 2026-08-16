package app.healthtrack.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

val Ink = Color(0xFF071016)
val InkLift = Color(0xFF10181E)
val Mint = Color(0xFF2EE6C8)
val Foam = Color(0xFFF3F7F6)
val Mist = Color(0xFF8AA0A8)
val Amber = Color(0xFFF5C16C)
val Pulse = Color(0xFF3CF0D2)
val GridLine = Color(0xFF1C3338)
val Danger = Color(0xFFFF7A7A)

@Composable
fun HealthTrackWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme(
            primary = Mint,
            primaryDim = Color(0xFF1A8F7C),
            onPrimary = Color(0xFF00382F),
            secondary = Amber,
            onSecondary = Color(0xFF2B2100),
            background = Ink,
            onBackground = Foam,
            onSurface = Foam,
            onSurfaceVariant = Mist,
            error = Danger,
            onError = Foam,
        ),
        content = content,
    )
}

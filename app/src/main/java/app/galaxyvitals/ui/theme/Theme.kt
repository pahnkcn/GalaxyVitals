package app.galaxyvitals.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF071016)
val InkLift = Color(0xFF10181E)
val InkHigh = Color(0xFF172229)
val Mint = Color(0xFF2EE6C8)
val MintDim = Color(0xFF1A8F7C)
val Foam = Color(0xFFF3F7F6)
val Mist = Color(0xFF8AA0A8)
val Amber = Color(0xFFF5C16C)
val Pulse = Color(0xFF3CF0D2)
val GridLine = Color(0xFF1C3338)
val Danger = Color(0xFFFF7A7A)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00382F),
    primaryContainer = MintDim,
    onPrimaryContainer = Foam,
    secondary = Amber,
    onSecondary = Color(0xFF2B2100),
    background = Ink,
    onBackground = Foam,
    surface = InkLift,
    onSurface = Foam,
    surfaceVariant = InkHigh,
    onSurfaceVariant = Mist,
    outline = Color(0xFF2A3D44),
    error = Danger,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B7A6B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8F6ED),
    onPrimaryContainer = Color(0xFF00382F),
    secondary = Color(0xFF8A6400),
    background = Color(0xFFF4F8F7),
    onBackground = Color(0xFF102026),
    surface = Color.White,
    onSurface = Color(0xFF102026),
    surfaceVariant = Color(0xFFE6EEEF),
    onSurfaceVariant = Color(0xFF4A6068),
    outline = Color(0xFFC5D3D6),
)

@Composable
fun HealthTrackTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

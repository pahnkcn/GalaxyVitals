package app.galaxyvitals.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * The phone's palette, on a screen that is always dark.
 *
 * Same rule, same three result colours: chrome and the trace are one bone
 * neutral, and the only chroma the watch ever shows is the verdict at the end
 * of a recording. A watch face lit by an accent would spend the signal that has
 * to survive being read at arm's length in daylight.
 */
val Paper0 = Color(0xFF141110)
val Paper1 = Color(0xFF1D1918)
val Paper2 = Color(0xFF282221)
val Rule = Color(0xFF332B29)

val Bone = Color(0xFFF2EDE9)
val Ash = Color(0xFF948A85)

val Regular = Color(0xFF5FCFA4)
val Irregular = Color(0xFFFF6F5E)
val Unclear = Color(0xFFE3B058)

/** The strip's grid, at the one strength a 40 mm screen can resolve. */
val GridLine = Color(0xFF33221E)

@Composable
fun HealthTrackWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme(
            primary = Bone,
            primaryDim = Ash,
            primaryContainer = Paper2,
            onPrimary = Paper0,
            onPrimaryContainer = Bone,
            secondary = Ash,
            onSecondary = Paper0,
            background = Paper0,
            onBackground = Bone,
            surfaceContainer = Paper1,
            surfaceContainerLow = Paper0,
            surfaceContainerHigh = Paper2,
            onSurface = Bone,
            onSurfaceVariant = Ash,
            outline = Rule,
            outlineVariant = Rule,
            error = Irregular,
            onError = Paper0,
        ),
        content = content,
    )
}

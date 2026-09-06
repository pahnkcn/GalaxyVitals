package app.galaxyvitals.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import app.galaxyvitals.data.protocol.NaoLabel
import app.galaxyvitals.domain.AnalysisStatus

/**
 * Colour is the verdict.
 *
 * Every control, every label and the trace itself are drawn in one neutral, so
 * the only chroma on screen is the rhythm result. A reader who sees colour at
 * all has already been told the answer, before a single word is read. That rule
 * is the whole palette: adding a tinted accent anywhere else would make the
 * green in the corner one signal among several instead of the only one.
 *
 * The ground is a warm near-black rather than a device black, because the strip
 * it surrounds is a sheet of ECG paper and the two should look like one object.
 */

// Ground → surface → raised → rule. Dark.
val Paper0 = Color(0xFF141110)
val Paper1 = Color(0xFF1D1918)
val Paper2 = Color(0xFF282221)
val Rule = Color(0xFF332B29)

// The one neutral pair. Text, controls and the trace are all Bone.
val Bone = Color(0xFFF2EDE9)
val Ash = Color(0xFF948A85)

// The only chroma in the app.
val Regular = Color(0xFF5FCFA4)
val Irregular = Color(0xFFFF6F5E)
val Unclear = Color(0xFFE3B058)

// Light: real paper, real clinical grid.
val Paper0Light = Color(0xFFFBF8F5)
val Paper1Light = Color(0xFFFFFFFF)
val Paper2Light = Color(0xFFEFE7E2)
val RuleLight = Color(0xFFE2D7D0)
val BoneLight = Color(0xFF17110E)
val AshLight = Color(0xFF6E635E)
val RegularLight = Color(0xFF0E9B6C)
val IrregularLight = Color(0xFFD6402F)
val UnclearLight = Color(0xFF9A6B12)

/** The strip's own palette, kept out of the Material scheme because nothing else uses it. */
@Immutable
data class EcgPaper(
    val paper: Color,
    val gridMinor: Color,
    val gridMajor: Color,
    val trace: Color,
    val marker: Color,
    val annotation: Color,
)

private val DarkPaper = EcgPaper(
    paper = Paper1,
    gridMinor = Color(0xFF33221E),
    gridMajor = Color(0xFF56302B),
    trace = Bone,
    marker = Ash,
    annotation = Ash,
)

private val LightPaper = EcgPaper(
    paper = Paper1Light,
    gridMinor = Color(0xFFF4D2C9),
    gridMajor = Color(0xFFE4A093),
    trace = BoneLight,
    marker = AshLight,
    annotation = AshLight,
)

/**
 * The three result colours, resolved for the current theme.
 *
 * They live in their own local rather than in the Material scheme so that no
 * component can pick one up by accident: a verdict colour is only ever applied
 * by asking for it.
 */
@Immutable
data class VerdictColors(
    val regular: Color,
    val irregular: Color,
    val unclear: Color,
)

private val DarkVerdicts = VerdictColors(Regular, Irregular, Unclear)
private val LightVerdicts = VerdictColors(RegularLight, IrregularLight, UnclearLight)

val LocalEcgPaper = staticCompositionLocalOf { DarkPaper }
val LocalVerdictColors = staticCompositionLocalOf { DarkVerdicts }

/** The single mapping from a result to its colour, shared by every screen. */
@Composable
fun verdictColor(status: AnalysisStatus, label: NaoLabel?): Color {
    val colors = LocalVerdictColors.current
    if (status != AnalysisStatus.OK) return colors.unclear
    return when (label) {
        NaoLabel.N -> colors.regular
        NaoLabel.A -> colors.irregular
        else -> colors.unclear
    }
}

private val DarkColors = darkColorScheme(
    primary = Bone,
    onPrimary = Paper0,
    primaryContainer = Paper2,
    onPrimaryContainer = Bone,
    secondary = Ash,
    onSecondary = Paper0,
    background = Paper0,
    onBackground = Bone,
    surface = Paper1,
    onSurface = Bone,
    surfaceVariant = Paper2,
    onSurfaceVariant = Ash,
    outline = Rule,
    outlineVariant = Rule,
    error = Irregular,
    onError = Paper0,
)

private val LightColors = lightColorScheme(
    primary = BoneLight,
    onPrimary = Paper0Light,
    primaryContainer = Paper2Light,
    onPrimaryContainer = BoneLight,
    secondary = AshLight,
    onSecondary = Paper0Light,
    background = Paper0Light,
    onBackground = BoneLight,
    surface = Paper1Light,
    onSurface = BoneLight,
    surfaceVariant = Paper2Light,
    onSurfaceVariant = AshLight,
    outline = RuleLight,
    outlineVariant = RuleLight,
    error = IrregularLight,
    onError = Paper0Light,
)

@Composable
fun HealthTrackTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalEcgPaper provides if (darkTheme) DarkPaper else LightPaper,
        LocalVerdictColors provides if (darkTheme) DarkVerdicts else LightVerdicts,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            content = content,
        )
    }
}

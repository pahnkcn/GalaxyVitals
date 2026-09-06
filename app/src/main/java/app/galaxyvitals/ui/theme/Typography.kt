package app.galaxyvitals.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Three type roles, all from fonts the device already has.
 *
 * Nothing is bundled: the platform faces already carry every locale the app
 * ships in, Thai included, and a display face would add weight to an APK whose
 * personality comes from scale rather than from letterforms. Exactly one thing
 * on any screen is large — the rate — and nothing else rises above 18 sp.
 *
 * Numbers are monospaced because a column of measurements has to line up on the
 * decimal to be readable, and because a fixed-pitch readout is the vernacular of
 * every instrument that prints an ECG. Annotations are condensed and tracked,
 * the way lead and scale marks print along the edge of real ECG paper.
 */
val DataFont: FontFamily = FontFamily.Monospace

val LabelFont: FontFamily = FontFamily(Font(DeviceFontFamilyName("sans-serif-condensed")))

/** Type roles Material3 has no slot for. */
object EcgType {
    /** The answer. One per screen, never two. */
    val dataHero = TextStyle(
        fontFamily = DataFont,
        fontSize = 78.sp,
        lineHeight = 76.sp,
        fontWeight = FontWeight.ExtraLight,
        letterSpacing = (-3.5).sp,
    )
    val dataDisplay = TextStyle(
        fontFamily = DataFont,
        fontSize = 56.sp,
        lineHeight = 56.sp,
        fontWeight = FontWeight.ExtraLight,
        letterSpacing = (-2.4).sp,
    )
    val dataLarge = TextStyle(
        fontFamily = DataFont,
        fontSize = 17.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Light,
    )
    val dataMedium = TextStyle(
        fontFamily = DataFont,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    )
    val dataSmall = TextStyle(
        fontFamily = DataFont,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )

    /**
     * Lead and scale marks printed on the paper: "LEAD I", "0–10 s", "BPM".
     * Latin and numerals only, which is why it can be condensed and tracked.
     * Translated wording uses [labelStyle] instead.
     */
    val annotation = TextStyle(
        fontFamily = LabelFont,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.6.sp,
    )
}

/**
 * The annotation role, for wording that gets translated.
 *
 * Thai has no capitals and no tradition of letter-spacing, and
 * `sans-serif-condensed` carries no Thai glyphs, so the tracked condensed
 * treatment falls back per-glyph and reads as loose and mismatched. In Thai the
 * same role keeps its size and colour but drops the tracking, takes the default
 * family, and gains line height for tone marks above and vowels below.
 */
@Composable
fun labelStyle(): TextStyle {
    val locale = LocalConfiguration.current.locales[0]
    return if (locale.language == "th") {
        TextStyle(
            fontSize = 12.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.sp,
        )
    } else {
        EcgType.annotation
    }
}

val AppTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Medium),
        headlineMedium = base.headlineMedium.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium),
        headlineSmall = base.headlineSmall.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium),
        titleLarge = base.titleLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium),
        titleMedium = base.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        bodyMedium = base.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
    )
}

/**
 * The layout scale is ECG paper's own.
 *
 * A dp is a 160th of an inch, so one millimetre of paper is 160/25.4 dp. Every
 * gap in the app is a whole or half millimetre at that rate, which means the
 * chrome and the grid inside the strip are measured in the same unit — the one
 * unit this app already calibrates against a real ruler in Settings.
 */
const val DP_PER_MM: Float = 6.29921f

/** Millimetres of ECG paper, as layout. */
val Float.mm: Dp get() = (this * DP_PER_MM).dp

object Spacing {
    val hair = 0.5f.mm      // 3.1 dp
    val tight = 1f.mm       // 6.3 dp
    val item = 2f.mm        // 12.6 dp
    val card = 2.5f.mm      // 15.7 dp
    val page = 3f.mm        // 18.9 dp
    val section = 4f.mm     // 25.2 dp
    /** One large box on the paper. */
    val box = 5f.mm         // 31.5 dp
}

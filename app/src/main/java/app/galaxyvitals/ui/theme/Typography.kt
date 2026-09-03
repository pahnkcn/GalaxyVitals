package app.galaxyvitals.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Three type roles, all from fonts the device already has.
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
    val dataHero = TextStyle(
        fontFamily = DataFont,
        fontSize = 56.sp,
        lineHeight = 58.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = (-2).sp,
    )
    val dataLarge = TextStyle(
        fontFamily = DataFont,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
    )
    val dataMedium = TextStyle(
        fontFamily = DataFont,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    )
    val dataSmall = TextStyle(
        fontFamily = DataFont,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

    /** Lead and scale marks on the strip, and section rules above it. */
    val annotation = TextStyle(
        fontFamily = LabelFont,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.1.sp,
    )
}

val AppTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = base.labelLarge.copy(fontFamily = LabelFont, letterSpacing = 0.9.sp),
        labelMedium = base.labelMedium.copy(fontFamily = LabelFont, letterSpacing = 0.9.sp),
        labelSmall = base.labelSmall.copy(fontFamily = LabelFont, letterSpacing = 0.9.sp),
    )
}

object Spacing {
    val page = 20.dp
    val section = 26.dp
    val card = 16.dp
    val item = 12.dp
    val tight = 6.dp
    val hair = 3.dp
}

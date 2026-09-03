package app.galaxyvitals.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How many screen pixels make one real millimetre.
 *
 * A strip is only measurable if its grid is the size it claims to be, and a
 * panel's reported DPI is often a rounded marketing figure rather than the
 * glass. So the reported value is the starting point and the user can correct it
 * against a ruler once; everything drawn at true scale follows that correction.
 */
class EcgScaleCalibration(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _factor = MutableStateFlow(
        prefs.getFloat(KEY_FACTOR, DEFAULT_FACTOR).coerceIn(MIN_FACTOR, MAX_FACTOR),
    )

    /** Multiplier over the screen's own reported density. 1.0 trusts the screen. */
    val factor: StateFlow<Float> = _factor

    fun setFactor(value: Float) {
        val clamped = value.coerceIn(MIN_FACTOR, MAX_FACTOR)
        _factor.value = clamped
        prefs.edit().putFloat(KEY_FACTOR, clamped).apply()
    }

    fun reset() = setFactor(DEFAULT_FACTOR)

    companion object {
        private const val PREFS_NAME = "display_prefs"
        private const val KEY_FACTOR = "mm_scale_factor"
        const val DEFAULT_FACTOR = 1f
        const val MIN_FACTOR = 0.6f
        const val MAX_FACTOR = 1.6f

        const val MM_PER_INCH = 25.4f

        /**
         * Pixels per millimetre as the panel reports itself. `xdpi` can come back
         * as nonsense on some devices, so an implausible value falls back to the
         * density bucket, which is at least the right order of magnitude.
         */
        fun reportedPxPerMm(context: Context): Float {
            val metrics = context.resources.displayMetrics
            val dpi = metrics.xdpi.takeIf { it in 80f..1200f } ?: metrics.densityDpi.toFloat()
            return dpi / MM_PER_INCH
        }
    }
}

/** Pixels per millimetre, corrected by whatever the user calibrated. */
@Composable
fun pxPerMm(calibration: EcgScaleCalibration): Float {
    val reported = EcgScaleCalibration.reportedPxPerMm(LocalContext.current)
    val factor by calibration.factor.collectAsState()
    return reported * factor
}

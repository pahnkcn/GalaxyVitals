package app.galaxyvitals.wear.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.foundation.LocalReduceMotion
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The watch keeps time with the wrist it is on.
 *
 * The same envelope the phone uses, so a beat looks the same on both screens:
 * a short sharp rise, a slower fall, then a rest until the next beat.
 */
/**
 * Two different switches, either of which should stop the beat.
 *
 * [LocalReduceMotion] is Wear's own "reduce motion" setting, which the platform
 * components already consult; the animator duration scale is what developer
 * options and the system accessibility toggle write. Reading the local rather
 * than remembering it also means the screen reacts when the setting changes,
 * which a value captured once never did.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    val animatorsOff = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }.getOrDefault(1f) == 0f
    return LocalReduceMotion.current || animatorsOff
}

@Composable
fun rememberBeatPulse(bpm: Double?): State<Float> {
    val reduce = rememberReduceMotion()
    val scale = remember { Animatable(1f) }
    val quantised = bpm
        ?.takeIf { it.isFinite() && !reduce }
        ?.let { (it.roundToInt().coerceIn(40, 180) / 6) * 6 }

    LaunchedEffect(quantised) {
        if (quantised == null) {
            scale.snapTo(1f)
            return@LaunchedEffect
        }
        val beatMs = (60_000f / quantised).toInt().coerceIn(333, 1_500)
        val systole = (beatMs * 0.18f).toInt().coerceAtLeast(70)
        val diastole = (beatMs * 0.22f).toInt().coerceAtLeast(80)
        val rest = (beatMs - systole - diastole).coerceAtLeast(40)
        while (true) {
            scale.snapTo(1f)
            scale.animateTo(1.28f, tween(systole, easing = FastOutSlowInEasing))
            scale.animateTo(1f, tween(diastole, easing = FastOutSlowInEasing))
            delay(rest.toLong())
        }
    }
    return scale.asState()
}

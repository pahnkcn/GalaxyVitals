package app.galaxyvitals.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The app's tempo is the wearer's own.
 *
 * Every ambient animation here takes its period from a measured rate rather
 * than from a designer's constant, so a fast heart makes a fast interface. The
 * envelope is the heartbeat's: a short sharp rise, a slower fall, then a rest
 * until the next beat — which is why it reads as a pulse instead of a throb.
 */

/**
 * True when the device is set to remove animation.
 *
 * Android exposes this as a zero animator duration scale, which is what both
 * "Remove animations" in accessibility settings and the developer-options
 * animation switches write to.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f) == 0f
    }
}

/** Systole is roughly a fifth of the cycle; the fall is a little longer. */
private const val SYSTOLE_SHARE = 0.18f
private const val DIASTOLE_SHARE = 0.22f
private const val PULSE_PEAK = 1.28f

/**
 * A 1f‑to‑[PULSE_PEAK] scale that beats once per cardiac cycle at [bpm].
 *
 * Returns a steady 1f when there is no rate to beat at, or when the device asks
 * for reduced motion, so a caller can apply it unconditionally.
 */
@Composable
fun rememberBeatPulse(bpm: Double?): State<Float> {
    val reduce = rememberReduceMotion()
    val scale = remember { Animatable(1f) }
    // Quantising to 6 bpm keeps a jittery live estimate from restarting the
    // animation on every sample while still tracking a real change in rate.
    val quantised = bpm
        ?.takeIf { it.isFinite() && !reduce }
        ?.let { (it.roundToInt().coerceIn(40, 180) / 6) * 6 }

    LaunchedEffect(quantised) {
        if (quantised == null) {
            scale.snapTo(1f)
            return@LaunchedEffect
        }
        val beatMs = (60_000f / quantised).toInt().coerceIn(333, 1_500)
        val systole = (beatMs * SYSTOLE_SHARE).toInt().coerceAtLeast(70)
        val diastole = (beatMs * DIASTOLE_SHARE).toInt().coerceAtLeast(80)
        val rest = (beatMs - systole - diastole).coerceAtLeast(40)
        while (true) {
            scale.snapTo(1f)
            scale.animateTo(PULSE_PEAK, tween(systole, easing = FastOutSlowInEasing))
            scale.animateTo(1f, tween(diastole, easing = FastOutSlowInEasing))
            delay(rest.toLong())
        }
    }
    return scale.asState()
}

/**
 * 0f to 1f once, when [key] changes: the fraction of the trace that has been
 * drawn. The strip strokes itself left to right the way a monitor prints, which
 * is the app's one orchestrated moment.
 *
 * Under reduced motion it is 1f from the first frame, so the strip is simply
 * there.
 */
@Composable
fun rememberSweep(key: Any?, durationMs: Int = SWEEP_MS): State<Float> {
    val reduce = rememberReduceMotion()
    val progress = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(key, reduce) {
        if (reduce) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMs, easing = LinearEasing))
    }
    return progress.asState()
}

const val SWEEP_MS: Int = 1_100

/** The verdict colour washing in behind the answer. */
const val WASH_MS: Int = 320

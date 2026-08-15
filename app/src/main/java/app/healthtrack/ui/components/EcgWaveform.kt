package app.healthtrack.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.healthtrack.domain.EcgSample
import app.healthtrack.ui.theme.GridLine
import app.healthtrack.ui.theme.HealthTrackTheme
import app.healthtrack.ui.theme.Pulse
import kotlin.math.max
import kotlin.math.min

@Composable
fun EcgWaveform(
    samples: List<EcgSample>,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableFloatStateOf(0f) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(if (interactive) 220.dp else 96.dp)
            .then(
                if (interactive) {
                    Modifier.pointerInput(samples) {
                        detectTransformGestures { _, panChange, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 12f)
                            pan += panChange.x
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        if (samples.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val cols = 25
        val rows = 8
        for (c in 0..cols) {
            val x = w * c / cols
            drawLine(GridLine, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }
        for (r in 0..rows) {
            val y = h * r / rows
            drawLine(GridLine, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }

        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        samples.forEach {
            minV = min(minV, it.valueMv)
            maxV = max(maxV, it.valueMv)
        }
        val span = (maxV - minV).coerceAtLeast(0.4f)
        val mid = (maxV + minV) / 2f
        val visible = (samples.size / scale).toInt().coerceAtLeast(8)
        val maxPan = ((samples.size - visible) * (w / visible)).coerceAtLeast(0f)
        val clampedPan = pan.coerceIn(-maxPan, 0f)
        val start = ((-clampedPan / w) * visible).toInt().coerceIn(0, samples.lastIndex)
        val end = (start + visible).coerceAtMost(samples.size)
        val slice = samples.subList(start, end)
        if (slice.size < 2) return@Canvas

        val path = Path()
        slice.forEachIndexed { index, sample ->
            val x = w * index / (slice.size - 1).coerceAtLeast(1)
            val y = h / 2f - ((sample.valueMv - mid) / span) * (h * 0.78f)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = Pulse,
            style = Stroke(width = 3f, cap = StrokeCap.Round),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF071016)
@Composable
private fun EcgWaveformPreview() {
    HealthTrackTheme(darkTheme = true) {
        val demo = List(400) { i ->
            val t = i / 500.0
            val qrs = if (i % 80 in 38..42) 1.6f else 0f
            EcgSample(i * 2L, (0.12f * kotlin.math.sin(t * 12).toFloat()) + qrs, 68)
        }
        EcgWaveform(samples = demo)
    }
}

package app.galaxyvitals.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.ui.theme.GridLine
import app.galaxyvitals.ui.theme.HealthTrackTheme
import app.galaxyvitals.ui.theme.Pulse
import kotlin.math.max
import kotlin.math.min

/**
 * Reduces dense waveform data without averaging away narrow QRS peaks.
 *
 * [maxPoints] is the number of time buckets. Each bucket contributes its
 * minimum and maximum value in their original chronological order, so the
 * result contains at most twice that many samples.
 */
internal fun reduceWaveform(samples: List<EcgSample>, maxPoints: Int): List<EcgSample> {
    if (samples.isEmpty() || maxPoints <= 0) return emptyList()
    if (samples.size.toLong() <= maxPoints.toLong() * 2L) return samples

    val bucketCount = min(maxPoints, samples.size)
    val reduced = ArrayList<EcgSample>(bucketCount * 2)
    for (bucket in 0 until bucketCount) {
        val start = (bucket.toLong() * samples.size / bucketCount).toInt()
        val endExclusive = (((bucket + 1L) * samples.size) / bucketCount).toInt()
            .coerceAtLeast(start + 1)
        var minIndex = start
        var maxIndex = start
        for (index in (start + 1) until endExclusive) {
            if (samples[index].valueMv < samples[minIndex].valueMv) minIndex = index
            if (samples[index].valueMv > samples[maxIndex].valueMv) maxIndex = index
        }
        when {
            minIndex == maxIndex -> reduced += samples[minIndex]
            minIndex < maxIndex -> {
                reduced += samples[minIndex]
                reduced += samples[maxIndex]
            }
            else -> {
                reduced += samples[maxIndex]
                reduced += samples[minIndex]
            }
        }
    }
    return reduced
}

@Composable
fun EcgWaveform(
    samples: List<EcgSample>,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableFloatStateOf(0f) }

    Column(modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (interactive) 220.dp else 96.dp)
                .then(
                    if (interactive) {
                        Modifier.pointerInput(samples) {
                            detectHorizontalDragGestures { _, dragAmount ->
                                pan += dragAmount
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

            val visible = (samples.size / scale).toInt().coerceAtLeast(8)
            val maxPan = ((samples.size - visible) * (w / visible)).coerceAtLeast(0f)
            val clampedPan = pan.coerceIn(-maxPan, 0f)
            val start = ((-clampedPan / w) * visible).toInt().coerceIn(0, samples.lastIndex)
            val end = (start + visible).coerceAtMost(samples.size)
            val slice = samples.subList(start, end)
            if (slice.size < 2) return@Canvas

            var minV = Float.POSITIVE_INFINITY
            var maxV = Float.NEGATIVE_INFINITY
            slice.forEach {
                minV = min(minV, it.valueMv)
                maxV = max(maxV, it.valueMv)
            }
            val span = (maxV - minV).coerceAtLeast(0.4f)
            val mid = (maxV + minV) / 2f
            val rendered = reduceWaveform(slice, w.toInt().coerceAtLeast(1))
            if (rendered.size < 2) return@Canvas
            val firstMs = slice.first().relMs
            val visibleDurationMs = (slice.last().relMs - firstMs).coerceAtLeast(1L)

            val path = Path()
            rendered.forEachIndexed { index, sample ->
                val x = if (slice.last().relMs > firstMs) {
                    w * (sample.relMs - firstMs).toFloat() / visibleDurationMs
                } else {
                    w * index / (rendered.size - 1).coerceAtLeast(1)
                }
                val y = h / 2f - ((sample.valueMv - mid) / span) * (h * 0.78f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = Pulse,
                style = Stroke(width = 3f, cap = StrokeCap.Round),
            )
        }
        if (interactive) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { scale = (scale / 1.6f).coerceAtLeast(1f) }) { Text("–") }
                TextButton(onClick = { scale = 1f; pan = 0f }) { Text("Reset") }
                TextButton(onClick = { scale = (scale * 1.6f).coerceAtMost(12f) }) { Text("+") }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF071016)
@Composable
private fun EcgWaveformPreview() {
    HealthTrackTheme(darkTheme = true) {
        val previewSamples = List(400) { i ->
            val t = i / 500.0
            val qrs = if (i % 80 in 38..42) 1.6f else 0f
            EcgSample(i * 2L, (0.12f * kotlin.math.sin(t * 12).toFloat()) + qrs, 68)
        }
        EcgWaveform(samples = previewSamples)
    }
}

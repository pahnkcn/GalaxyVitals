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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.galaxyvitals.data.protocol.EcgWaveformGeometry
import app.galaxyvitals.data.protocol.WaveformPoint
import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.domain.EcgSampleFlags
import app.galaxyvitals.ui.theme.GridLine
import app.galaxyvitals.ui.theme.HealthTrackTheme
import app.galaxyvitals.ui.theme.Pulse
import kotlin.math.max
import kotlin.math.min

internal data class WaveformAmplitudeBounds(val mid: Float, val span: Float)

/**
 * Full-trace autoscale ignores the leading electrode-polarization swing so QRS
 * amplitude fills the chart. Zoomed/panned windows use the visible min/max.
 */
internal fun waveformAmplitudeBounds(
    samples: List<EcgSample>,
    viewingFromStart: Boolean,
): WaveformAmplitudeBounds {
    if (samples.isEmpty()) return WaveformAmplitudeBounds(mid = 0f, span = 0.4f)
    val skip = if (viewingFromStart && samples.size >= 15) {
        (samples.size / 15).coerceAtMost(samples.size / 4)
    } else {
        0
    }
    var minV = Float.POSITIVE_INFINITY
    var maxV = Float.NEGATIVE_INFINITY
    for (index in skip until samples.size) {
        val value = samples[index].valueMv
        minV = min(minV, value)
        maxV = max(maxV, value)
    }
    return WaveformAmplitudeBounds(
        mid = (maxV + minV) / 2f,
        span = (maxV - minV).coerceAtLeast(0.4f),
    )
}

internal fun toWaveformPoints(samples: List<EcgSample>): List<WaveformPoint> {
    val gapFlags = EcgSampleFlags.TIMESTAMP_GAP or EcgSampleFlags.SEQUENCE_GAP
    return samples.mapIndexed { index, sample ->
        WaveformPoint(
            sampleIndex = sample.sampleIndex.toLong(),
            valueMv = sample.valueMv,
            startsNewSegment = index == 0 || sample.flags and gapFlags != 0,
        )
    }
}

internal fun reduceWaveform(samples: List<EcgSample>, physicalPixelWidth: Int): List<WaveformPoint> {
    val points = toWaveformPoints(samples)
    if (points.isEmpty()) return emptyList()
    return EcgWaveformGeometry.reduceM4(
        points,
        physicalPixelWidth,
        firstSampleIndex = points.first().sampleIndex,
        lastSampleIndex = points.last().sampleIndex,
    )
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

            val bounds = waveformAmplitudeBounds(slice, viewingFromStart = start == 0)
            val span = bounds.span
            val mid = bounds.mid
            val rendered = reduceWaveform(slice, w.toInt().coerceAtLeast(1))
            if (rendered.size < 2) return@Canvas

            // Samples are a uniform fixed-rate stream, so x follows sampleIndex;
            // relMs cannot be trusted because captured sensor timestamps are
            // batch-quantized and would collapse many samples onto one x.
            val firstSampleIndex = slice.first().sampleIndex.toLong()
            val lastSampleIndex = slice.last().sampleIndex.toLong()
            val indexSpan = (lastSampleIndex - firstSampleIndex).coerceAtLeast(1L)
            val strokeWidth = 3f
            val path = Path()
            rendered.forEach { point ->
                val x = w * ((point.sampleIndex - firstSampleIndex).toDouble() / indexSpan).toFloat()
                val y = EcgWaveformGeometry.mapYToCanvas(
                    valueMv = point.valueMv,
                    centerMv = mid,
                    halfRangeMv = (span / 2f).coerceAtLeast(0.2f),
                    heightPx = h,
                    strokeWidthPx = strokeWidth,
                )
                if (point.startsNewSegment) path.moveTo(x, y) else path.lineTo(x, y)
            }
            clipRect(0f, 0f, w, h) {
                drawPath(
                    path = path,
                    color = Pulse,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
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
            EcgSample(i * 2L, (0.12f * kotlin.math.sin(t * 12).toFloat()) + qrs, 68, i)
        }
        EcgWaveform(samples = previewSamples)
    }
}

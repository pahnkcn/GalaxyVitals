package app.galaxyvitals.wear.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import app.galaxyvitals.data.protocol.EcgWaveformGeometry
import app.galaxyvitals.wear.ui.LiveWaveformFrame
import app.galaxyvitals.wear.ui.theme.GridLine
import app.galaxyvitals.wear.ui.theme.Pulse

@Composable
fun EcgWaveformMini(
    frame: LiveWaveformFrame,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        val w = size.width
        val h = size.height
        for (c in 0..12) {
            drawLine(GridLine, Offset(w * c / 12f, 0f), Offset(w * c / 12f, h), strokeWidth = 1f)
        }
        for (r in 0..4) {
            drawLine(GridLine, Offset(0f, h * r / 4f), Offset(w, h * r / 4f), strokeWidth = 1f)
        }
        if (frame.points.size < 2) return@Canvas
        val strokeWidth = 2.5f
        val rendered = EcgWaveformGeometry.reduceM4(
            frame.points,
            physicalPixelWidth = size.width.toInt().coerceAtLeast(1),
            firstSampleIndex = frame.firstSampleIndex,
            lastSampleIndex = frame.lastSampleIndex,
        )
        if (rendered.isEmpty()) return@Canvas
        val path = Path()
        rendered.forEach { point ->
            val xRatio = (
                (point.sampleIndex - frame.firstSampleIndex).toDouble() /
                    (frame.lastSampleIndex - frame.firstSampleIndex).coerceAtLeast(1L)
                ).toFloat()
            val x = size.width * xRatio
            val y = EcgWaveformGeometry.mapYToCanvas(
                valueMv = point.valueMv,
                centerMv = frame.scale.centerMv,
                halfRangeMv = frame.scale.halfRangeMv,
                heightPx = size.height,
                strokeWidthPx = strokeWidth,
            )
            if (point.startsNewSegment) path.moveTo(x, y) else path.lineTo(x, y)
        }
        clipRect(0f, 0f, size.width, size.height) {
            drawPath(path, Pulse, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
        }
    }
}

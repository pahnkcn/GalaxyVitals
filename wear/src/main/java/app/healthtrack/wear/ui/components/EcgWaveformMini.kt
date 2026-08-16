package app.healthtrack.wear.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.healthtrack.wear.ui.theme.GridLine
import app.healthtrack.wear.ui.theme.Pulse
import kotlin.math.max
import kotlin.math.min

@Composable
fun EcgWaveformMini(
    values: List<Float>,
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
        if (values.size < 2) return@Canvas
        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        values.forEach {
            minV = min(minV, it)
            maxV = max(maxV, it)
        }
        val span = (maxV - minV).coerceAtLeast(0.4f)
        val mid = (maxV + minV) / 2f
        val path = Path()
        values.forEachIndexed { index, sample ->
            val x = w * index / (values.size - 1).coerceAtLeast(1)
            val y = h / 2f - ((sample - mid) / span) * (h * 0.72f)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Pulse, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
    }
}

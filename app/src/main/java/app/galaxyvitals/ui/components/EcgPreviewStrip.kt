package app.galaxyvitals.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import app.galaxyvitals.ui.theme.DP_PER_MM
import app.galaxyvitals.ui.theme.LocalEcgPaper
import app.galaxyvitals.ui.theme.mm

/**
 * A few seconds of trace on real paper.
 *
 * Not a chart: the grid is 1 mm and 5 mm at the strip's own scale, and the
 * trace is drawn at the same 10 mm/mV gain the full sheet uses, so a glance
 * here and a measurement on the detail screen agree about the size of a beat.
 * The 1 mm grid is only drawn once it can actually resolve; below that it would
 * be a wash of colour pretending to be a grid.
 */
@Composable
fun EcgPreviewStrip(
    values: List<Float>,
    modifier: Modifier = Modifier,
    height: Dp = 11f.mm,
    sweep: State<Float>? = null,
) {
    val paper = LocalEcgPaper.current
    // One millimetre of paper in pixels: dp per mm, at this screen's density.
    val pxPerMm = DP_PER_MM * LocalDensity.current.density

    Canvas(modifier.fillMaxWidth().height(height)) {
        drawRect(paper.paper)
        drawPaperGrid(pxPerMm, paper.gridMinor, paper.gridMajor)
        if (values.size < 2) return@Canvas

        val baseline = size.height * 0.62f
        val gainPx = pxPerMm * MM_PER_MV
        val path = Path()
        values.forEachIndexed { index, mv ->
            val x = size.width * index / (values.size - 1).toFloat()
            val y = baseline - mv * gainPx
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawTrace(path, paper.trace, STROKE_PX, sweep?.value ?: 1f)
    }
}

/** The shape of one recording, at row height. No grid: a row is not measured. */
@Composable
fun EcgRowSparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = LocalEcgPaper.current.marker,
) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        var min = values[0]
        var max = values[0]
        values.forEach {
            if (it < min) min = it
            if (it > max) max = it
        }
        val span = (max - min).coerceAtLeast(0.4f)
        val inset = STROKE_PX
        val usable = (size.height - inset * 2).coerceAtLeast(1f)
        val path = Path()
        values.forEachIndexed { index, mv ->
            val x = size.width * index / (values.size - 1).toFloat()
            val y = inset + usable * (1f - (mv - min) / span)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = STROKE_PX, cap = StrokeCap.Round))
    }
}

/** 1 mm and 5 mm rules, drawn from the centre out so the major lines stay put. */
private fun DrawScope.drawPaperGrid(pxPerMm: Float, minor: Color, major: Color) {
    val majorStep = pxPerMm * 5f
    if (pxPerMm >= MIN_RESOLVABLE_MM_PX) {
        var x = 0f
        while (x <= size.width) {
            drawLine(minor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += pxPerMm
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(minor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += pxPerMm
        }
    }
    var x = 0f
    while (x <= size.width) {
        drawLine(major, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += majorStep
    }
    var y = 0f
    while (y <= size.height) {
        drawLine(major, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += majorStep
    }
}

/**
 * Strokes [path] up to [progress] of its length: the sweep, the way a monitor
 * prints a trace rather than revealing one that was already there.
 */
internal fun DrawScope.drawTrace(path: Path, color: Color, width: Float, progress: Float) {
    val stroke = Stroke(width = width, cap = StrokeCap.Round)
    if (progress >= 1f) {
        drawPath(path, color, style = stroke)
        return
    }
    if (progress <= 0f) return
    val measure = PathMeasure()
    measure.setPath(path, false)
    val drawn = Path()
    measure.getSegment(0f, measure.length * progress, drawn, true)
    drawPath(drawn, color, style = stroke)
}

private const val MM_PER_MV = 10f
private const val MIN_RESOLVABLE_MM_PX = 2.4f
private const val STROKE_PX = 2.2f

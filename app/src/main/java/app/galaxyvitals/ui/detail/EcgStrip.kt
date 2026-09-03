package app.galaxyvitals.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.galaxyvitals.R
import app.galaxyvitals.data.protocol.EcgStripLayout
import app.galaxyvitals.data.protocol.StripGrid
import app.galaxyvitals.data.protocol.StripRow
import app.galaxyvitals.data.protocol.StripSpec
import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.ui.components.reduceWaveform
import app.galaxyvitals.ui.theme.EcgType
import app.galaxyvitals.ui.theme.LocalEcgPaper
import kotlin.math.roundToInt

/**
 * The recording as a sheet of ECG paper: rows of trace over a 1 mm / 5 mm grid,
 * each opening with the 1 mV calibration pulse that says what the grid means.
 *
 * The same geometry serves both modes. [EcgSheetStrip] squeezes a whole sheet
 * into the screen so the shape of the recording is visible at a glance;
 * [EcgTrueScaleStrip] draws it at real millimetres and lets the user scroll, so
 * it can be measured. The PDF draws the sheet form at real millimetres too — one
 * layout, three surfaces.
 */

/** Everything already turned into pixels, so a frame just strokes paths. */
private class StripRender(
    val rows: List<StripRow>,
    val grid: StripGrid,
    val tracePaths: List<Path>,
    val calibrationPaths: List<Path>,
    val beatMarkers: List<Offset>,
    val pxPerMm: Float,
    val widthPx: Float,
    val heightPx: Float,
)

@Composable
fun EcgSheetStrip(
    samples: List<EcgSample>,
    durationSec: Double,
    srHz: Double,
    spec: StripSpec,
    rPeaksMs: List<Double>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val fit = EcgStripLayout.pxPerMmToFit(widthPx, spec)
        StripCanvas(
            samples = samples,
            durationSec = durationSec,
            srHz = srHz,
            spec = spec,
            rPeaksMs = rPeaksMs,
            pxPerMm = fit,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun EcgTrueScaleStrip(
    samples: List<EcgSample>,
    durationSec: Double,
    srHz: Double,
    spec: StripSpec,
    rPeaksMs: List<Double>,
    pxPerMm: Float,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val widthDp: Dp = with(LocalDensity.current) {
        (EcgStripLayout.widthMm(spec) * pxPerMm).toFloat().toDp()
    }
    Column(modifier.horizontalScroll(scroll)) {
        StripCanvas(
            samples = samples,
            durationSec = durationSec,
            srHz = srHz,
            spec = spec,
            rPeaksMs = rPeaksMs,
            pxPerMm = pxPerMm,
            modifier = Modifier.width(widthDp),
        )
    }
}

@Composable
private fun StripCanvas(
    samples: List<EcgSample>,
    durationSec: Double,
    srHz: Double,
    spec: StripSpec,
    rPeaksMs: List<Double>,
    pxPerMm: Float,
    modifier: Modifier = Modifier,
) {
    val paper = LocalEcgPaper.current
    val description = stringResource(
        R.string.strip_content_description,
        durationSec.roundToInt(),
        rPeaksMs.size,
    )
    val measurer = rememberTextMeasurer()
    val annotationStyle = EcgType.annotation.copy(color = paper.annotation)
    val render = remember(samples, durationSec, srHz, spec, rPeaksMs, pxPerMm) {
        buildRender(samples, durationSec, srHz, spec, rPeaksMs, pxPerMm)
    }
    val heightDp = with(LocalDensity.current) { render.heightPx.toDp() }

    Canvas(
        modifier
            .semantics { contentDescription = description }
            .height(heightDp)
            .clip(RoundedCornerShape(10.dp))
            .background(paper.paper),
    ) {
        if (render.pxPerMm <= 0f) return@Canvas
        drawGrid(render, paper.gridMinor, paper.gridMajor)
        val stroke = Stroke(
            width = (0.4f * render.pxPerMm).coerceIn(1.6f, 5f),
            cap = StrokeCap.Round,
        )
        render.calibrationPaths.forEach { drawPath(it, paper.trace, style = stroke) }
        render.tracePaths.forEach { drawPath(it, paper.trace, style = stroke) }
        drawBeatMarkers(render, paper.marker)
        drawAnnotations(render, measurer, annotationStyle)
    }
}

private fun DrawScope.drawGrid(render: StripRender, minor: Color, major: Color) {
    val f = render.pxPerMm
    val minorWidth = (0.06f * f).coerceAtLeast(0.7f)
    val majorWidth = (0.13f * f).coerceAtLeast(1.1f)
    render.grid.minorXMm.forEach { x ->
        val px = (x * f).toFloat()
        drawLine(minor, Offset(px, 0f), Offset(px, render.heightPx), minorWidth)
    }
    render.grid.minorYMm.forEach { y ->
        val py = (y * f).toFloat()
        drawLine(minor, Offset(0f, py), Offset(render.widthPx, py), minorWidth)
    }
    render.grid.majorXMm.forEach { x ->
        val px = (x * f).toFloat()
        drawLine(major, Offset(px, 0f), Offset(px, render.heightPx), majorWidth)
    }
    render.grid.majorYMm.forEach { y ->
        val py = (y * f).toFloat()
        drawLine(major, Offset(0f, py), Offset(render.widthPx, py), majorWidth)
    }
}

/**
 * A tick at the top of the lane over every accepted beat. It sits clear of the
 * trace on purpose: a marker drawn on the R wave hides the thing it is marking.
 */
private fun DrawScope.drawBeatMarkers(render: StripRender, color: Color) {
    val length = (1.6f * render.pxPerMm).coerceAtLeast(4f)
    val width = (0.25f * render.pxPerMm).coerceAtLeast(1.5f)
    render.beatMarkers.forEach { marker ->
        drawLine(
            color = color,
            start = marker,
            end = Offset(marker.x, marker.y + length),
            strokeWidth = width,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Row time ranges and the lead name. Deliberately silent about mm/s and mm/mV:
 * the caption under the strip says whether this drawing is to scale, and
 * stamping a scale onto a fitted sheet would claim something untrue.
 */
private fun DrawScope.drawAnnotations(
    render: StripRender,
    measurer: TextMeasurer,
    style: TextStyle,
) {
    val inset = (1.4f * render.pxPerMm).coerceAtLeast(6f)
    // Every row is labelled, the way a printed strip labels each lane: the row
    // is the unit a reader looks at, not the sheet.
    val lead = measurer.measure("I", style)
    render.rows.forEach { row ->
        val top = (row.topMm * render.pxPerMm).toFloat() + inset
        drawText(textLayoutResult = lead, topLeft = Offset(inset, top))
        val label = measurer.measure("${row.startSec.roundToInt()}–${row.endSec.roundToInt()} s", style)
        drawText(
            textLayoutResult = label,
            topLeft = Offset(render.widthPx - label.size.width - inset, top),
        )
    }
}

private fun buildRender(
    samples: List<EcgSample>,
    durationSec: Double,
    srHz: Double,
    spec: StripSpec,
    rPeaksMs: List<Double>,
    pxPerMm: Float,
): StripRender {
    val rows = EcgStripLayout.rows(durationSec, spec)
    val grid = EcgStripLayout.grid(rows.size, spec)
    val widthPx = (EcgStripLayout.widthMm(spec) * pxPerMm).toFloat()
    val heightPx = (EcgStripLayout.heightMm(rows.size, spec) * pxPerMm).toFloat()
    if (pxPerMm <= 0f) {
        return StripRender(rows, grid, emptyList(), emptyList(), emptyList(), pxPerMm, widthPx, heightPx)
    }

    val calibration = rows.map { row ->
        Path().apply {
            EcgStripLayout.calibrationPulseMm(row, spec).forEachIndexed { index, point ->
                val x = (point.xMm * pxPerMm).toFloat()
                val y = (point.yMm * pxPerMm).toFloat()
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
    }
    val traces = rows.map { row -> rowPath(samples, row, spec, srHz, pxPerMm) }
    val markers = beatMarkers(rPeaksMs, rows, spec, pxPerMm)

    return StripRender(rows, grid, traces, calibration, markers, pxPerMm, widthPx, heightPx)
}

private fun rowPath(
    samples: List<EcgSample>,
    row: StripRow,
    spec: StripSpec,
    srHz: Double,
    pxPerMm: Float,
): Path {
    val path = Path()
    val range = EcgStripLayout.rowSampleRange(row, srHz, samples.size)
    if (range.isEmpty()) return path

    // M4 keeps the min and max of every bucket, so a narrow QRS survives being
    // reduced to the width of the lane instead of being averaged away.
    val tracePx = ((EcgStripLayout.widthMm(spec) - spec.calGutterMm) * pxPerMm).roundToInt()
    val reduced = reduceWaveform(
        samples = samples.subList(range.first, range.last + 1),
        physicalPixelWidth = tracePx.coerceAtLeast(2),
    )
    if (reduced.size < 2) return path

    var started = false
    reduced.forEach { point ->
        val tSec = EcgStripLayout.sampleTimeSec(point.sampleIndex, srHz)
        val x = (EcgStripLayout.xMm(tSec, row, spec) * pxPerMm).toFloat()
        val y = (EcgStripLayout.yMm(point.valueMv.toDouble(), row, spec) * pxPerMm).toFloat()
        if (!started || point.startsNewSegment) {
            path.moveTo(x, y)
            started = true
        } else {
            path.lineTo(x, y)
        }
    }
    return path
}

private fun beatMarkers(
    rPeaksMs: List<Double>,
    rows: List<StripRow>,
    spec: StripSpec,
    pxPerMm: Float,
): List<Offset> = rPeaksMs.mapNotNull { peakMs ->
    val tSec = peakMs / 1000.0
    val row = rows.firstOrNull { tSec >= it.startSec && tSec < it.endSec } ?: return@mapNotNull null
    Offset(
        x = (EcgStripLayout.xMm(tSec, row, spec) * pxPerMm).toFloat(),
        y = (row.topMm * pxPerMm).toFloat(),
    )
}

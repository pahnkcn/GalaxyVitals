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
import app.galaxyvitals.wear.ui.theme.Bone
import app.galaxyvitals.wear.ui.theme.GridLine
import app.galaxyvitals.wear.ui.theme.Rule
import app.galaxyvitals.wear.ui.theme.faceDp

/**
 * The live trace, in a plate.
 *
 * The strip fills whatever plate it is given rather than sizing itself, because
 * on this layout it always sits in a Core-tier column with the readout above it
 * and the rail below: three straight left margins the eye can ride down. Sizing
 * itself to its own fraction of the screen would break that alignment.
 *
 * It carries a hairline frame, which the round version did not. A rectangle
 * drawn on a round screen needed no edge — the bezel was the edge. Here the
 * frame is what says this one region is a chart and not more page.
 *
 * There is no paper grid here. At this size a cell would be about two
 * millimetres of screen, which is neither the clinical 1 mm square nor legible
 * at arm's length; all it would do is halve the contrast of the one line worth
 * looking at. A single baseline gives the eye its zero and nothing else. The
 * real grid belongs on the phone's strip, where the metaphor has room to be
 * true and the trace can actually be measured.
 */
@Composable
fun EcgWaveformMini(
    frame: LiveWaveformFrame,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height((faceDp() * HEIGHT_FRACTION).dp),
    ) {
        drawRect(
            color = Rule,
            topLeft = Offset.Zero,
            size = size,
            style = Stroke(width = FRAME_DP.toPx()),
        )
        val baseline = size.height / 2f
        drawLine(
            color = GridLine,
            start = Offset(0f, baseline),
            end = Offset(size.width, baseline),
            strokeWidth = BASELINE_DP.toPx(),
        )
        if (frame.points.size < 2) return@Canvas

        // Pixels, not raw floats: a bare "2.5f" is a hairline once the watch's
        // display density is applied, which is why the trace used to read faint.
        val strokeWidth = TRACE_DP.toPx()
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
            drawPath(path, Bone, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
        }
    }
}

/** Roughly 4:1 against a Core plate, so a three-second window still shows a QRS. */
private const val HEIGHT_FRACTION = 0.17f
private val TRACE_DP = 2.dp
private val BASELINE_DP = 1.dp
private val FRAME_DP = 1.dp

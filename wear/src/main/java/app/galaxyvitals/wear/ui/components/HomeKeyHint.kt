package app.galaxyvitals.wear.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import app.galaxyvitals.wear.ui.theme.Mist
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Galaxy Watch ECG electrode is the upper (Home) key on the 3 o'clock side. */
internal data class HomeKeyHintLayout(
    val homeCenterX: Float,
    val homeCenterY: Float,
    val lowerCenterX: Float,
    val lowerCenterY: Float,
    val buttonWidth: Float,
    val buttonHeight: Float,
    val arrowTipX: Float,
    val arrowTipY: Float,
    val arrowTailX: Float,
    val arrowTailY: Float,
) {
    companion object {
        fun forCanvas(width: Float, height: Float): HomeKeyHintLayout {
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) / 2f
            val rim = radius * 0.88f
            val homeAngle = Math.toRadians(-22.0)
            val lowerAngle = Math.toRadians(22.0)
            val homeX = cx + rim * cos(homeAngle).toFloat()
            val homeY = cy + rim * sin(homeAngle).toFloat()
            val lowerX = cx + rim * cos(lowerAngle).toFloat()
            val lowerY = cy + rim * sin(lowerAngle).toFloat()
            val buttonWidth = radius * 0.10f
            val buttonHeight = radius * 0.18f
            val arrowTipX = homeX - buttonWidth * 0.85f
            val arrowTipY = homeY
            val arrowTailX = arrowTipX - radius * 0.26f
            return HomeKeyHintLayout(
                homeCenterX = homeX,
                homeCenterY = homeY,
                lowerCenterX = lowerX,
                lowerCenterY = lowerY,
                buttonWidth = buttonWidth,
                buttonHeight = buttonHeight,
                arrowTipX = arrowTipX,
                arrowTipY = arrowTipY,
                arrowTailX = arrowTailX,
                arrowTailY = homeY,
            )
        }
    }
}

@Composable
fun HomeKeyHint(modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "home-key").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val accent = MaterialTheme.colorScheme.primary
    Canvas(
        modifier.semantics { contentDescription = "Touch the top button on the right" },
    ) {
        val layout = HomeKeyHintLayout.forCanvas(size.width, size.height)
        drawWatchButton(
            cx = layout.lowerCenterX,
            cy = layout.lowerCenterY,
            width = layout.buttonWidth,
            height = layout.buttonHeight,
            color = Mist.copy(alpha = 0.5f),
            filled = false,
        )
        drawCircle(
            color = accent.copy(alpha = 0.20f * pulse),
            radius = layout.buttonHeight * (0.55f + 0.28f * pulse),
            center = Offset(layout.homeCenterX, layout.homeCenterY),
        )
        drawWatchButton(
            cx = layout.homeCenterX,
            cy = layout.homeCenterY,
            width = layout.buttonWidth,
            height = layout.buttonHeight,
            color = accent.copy(alpha = 0.55f + 0.45f * pulse),
            filled = true,
        )
        drawHomeArrow(layout, nudge = pulse * 4f, color = accent)
    }
}

private fun DrawScope.drawWatchButton(
    cx: Float,
    cy: Float,
    width: Float,
    height: Float,
    color: Color,
    filled: Boolean,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(cx - width / 2f, cy - height / 2f),
        size = Size(width, height),
        cornerRadius = CornerRadius(width / 2f, width / 2f),
        style = if (filled) Fill else Stroke(width = 2.dp.toPx()),
    )
}

private fun DrawScope.drawHomeArrow(layout: HomeKeyHintLayout, nudge: Float, color: Color) {
    val tip = Offset(layout.arrowTipX + nudge, layout.arrowTipY)
    val tail = Offset(layout.arrowTailX + nudge, layout.arrowTailY)
    val head = layout.buttonWidth * 1.35f
    drawLine(
        color = color,
        start = tail,
        end = Offset(tip.x - head * 0.35f, tip.y),
        strokeWidth = 3.dp.toPx(),
        cap = StrokeCap.Round,
    )
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x - head, tip.y - head * 0.62f)
        lineTo(tip.x - head, tip.y + head * 0.62f)
        close()
    }
    drawPath(path, color)
}

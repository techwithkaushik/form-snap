package org.techwithkaushik.formsnap.capture

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.min

@Composable
fun CameraOverlayLayout(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    lineColor: Color = Color.White.copy(alpha = 0.42f),
    guideColor: Color = Color.White.copy(alpha = 0.92f),
    dimColor: Color = Color.Black.copy(alpha = 0.12f),
) {
    Canvas(modifier) {
        if (!enabled || size.minDimension <= 0f) return@Canvas

        drawRect(
            color = dimColor,
            topLeft = Offset.Zero,
            size = size,
        )

        val width = size.width
        val height = size.height
        val cellWidth = width / 3f
        val cellHeight = height / 3f
        val gridStroke = min(width, height).coerceAtLeast(1f) * 0.0022f
        val guideStroke = min(width, height).coerceAtLeast(1f) * 0.0042f

        for (column in 1..2) {
            val x = cellWidth * column
            drawLine(
                color = lineColor,
                start = Offset(x, 0f),
                end = Offset(x, height),
                strokeWidth = gridStroke.coerceAtLeast(1.dp.toPx()),
                cap = StrokeCap.Butt,
            )
        }

        for (row in 1..2) {
            val y = cellHeight * row
            drawLine(
                color = lineColor,
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = gridStroke.coerceAtLeast(1.dp.toPx()),
                cap = StrokeCap.Butt,
            )
        }

        val marginX = width * 0.06f
        val marginY = height * 0.10f
        val left = marginX
        val top = marginY
        val right = width - marginX
        val bottom = height - marginY
        val cornerLength = min(width, height) * 0.075f

        val guide = Path().apply {
            moveTo(left + cornerLength, top)
            lineTo(left, top)
            lineTo(left, top + cornerLength)

            moveTo(right - cornerLength, top)
            lineTo(right, top)
            lineTo(right, top + cornerLength)

            moveTo(left, bottom - cornerLength)
            lineTo(left, bottom)
            lineTo(left + cornerLength, bottom)

            moveTo(right - cornerLength, bottom)
            lineTo(right, bottom)
            lineTo(right, bottom - cornerLength)
        }

        drawPath(
            path = guide,
            color = guideColor,
            style = Stroke(
                width = guideStroke.coerceAtLeast(2.dp.toPx()),
                cap = StrokeCap.Round,
            ),
        )

        val center = Offset(width / 2f, height / 2f)
        val cross = min(width, height) * 0.025f
        drawLine(
            color = guideColor.copy(alpha = 0.68f),
            start = Offset(center.x - cross, center.y),
            end = Offset(center.x + cross, center.y),
            strokeWidth = gridStroke.coerceAtLeast(1.dp.toPx()),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = guideColor.copy(alpha = 0.68f),
            start = Offset(center.x, center.y - cross),
            end = Offset(center.x, center.y + cross),
            strokeWidth = gridStroke.coerceAtLeast(1.dp.toPx()),
            cap = StrokeCap.Round,
        )
    }
}

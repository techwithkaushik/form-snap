package org.techwithkaushik.formsnap.feature.capture

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

@Composable
fun NativeAlignedGrid(
    modifier: Modifier = Modifier,
    state: CaptureGridState = CaptureGridState()
) {
    if (!state.enabled) return

    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.003f
        val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        val x1 = size.width / 3f
        val x2 = size.width * 2f / 3f
        val y1 = size.height / 3f
        val y2 = size.height * 2f / 3f

        drawLine(
            color = lineColor,
            start = Offset(x1, 0f),
            end = Offset(x1, size.height),
            strokeWidth = strokeWidth,
        )
        drawLine(
            color = lineColor,
            start = Offset(x2, 0f),
            end = Offset(x2, size.height),
            strokeWidth = strokeWidth,
        )
        drawLine(
            color = lineColor,
            start = Offset(0f, y1),
            end = Offset(size.width, y1),
            strokeWidth = strokeWidth,
        )
        drawLine(
            color = lineColor,
            start = Offset(0f, y2),
            end = Offset(size.width, y2),
            strokeWidth = strokeWidth,
        )
    }
}

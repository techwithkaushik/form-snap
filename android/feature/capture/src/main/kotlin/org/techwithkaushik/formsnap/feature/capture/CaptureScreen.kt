package org.techwithkaushik.formsnap.feature.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun NativeAlignedGrid(
    modifier: Modifier = Modifier,
    state: CaptureGridState = CaptureGridState()
) {
    if (!state.enabled) return
    Canvas(modifier.fillMaxSize()) {
        val stroke = size.minDimension * 0.003f
        val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        val x1 = size.width / 3f
        val x2 = size.width * 2f / 3f
        val y1 = size.height / 3f
        val y2 = size.height * 2f / 3f
        drawLine(lineColor, x1, 0f, x1, size.height, stroke)
        drawLine(lineColor, x2, 0f, x2, size.height, stroke)
        drawLine(lineColor, 0f, y1, size.width, y1, stroke)
        drawLine(lineColor, 0f, y2, size.width, y2, stroke)
    }
}

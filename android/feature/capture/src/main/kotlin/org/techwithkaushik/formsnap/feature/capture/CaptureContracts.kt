package org.techwithkaushik.formsnap.feature.capture

import android.graphics.RectF

data class CaptureGridState(
    val enabled: Boolean = true,
    val horizontalDivisions: Int = 3,
    val verticalDivisions: Int = 3
)

data class CaptureResult(
    val inputPath: String,
    val frame: RectF? = null,
    val rotatedDegrees: Float = 0f
)

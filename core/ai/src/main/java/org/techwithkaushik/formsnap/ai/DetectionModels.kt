package org.techwithkaushik.formsnap.ai

import android.graphics.RectF

data class DetectedObject(
    val id: Long,
    val classId: Int,
    val label: String,
    val confidence: Float,
    val boundingBox: RectF,
) {
    /** Any non-negative model class ID can be previewed, selected, and exported. */
    val isExtractable: Boolean
        get() = classId >= 0
}

data class LetterboxTransform(
    val scale: Float,
    val padX: Float,
    val padY: Float,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val inputSize: Int,
    val inputWidth: Int = inputSize,
    val inputHeight: Int = inputSize,
)

data class DetectionConfig(
    val inputSize: Int = 320,
    val confidenceThreshold: Float = 0.35f,
    val iouThreshold: Float = 0.45f,
    val maxDetections: Int = 8,
    val classLabels: List<String> = emptyList(),
)

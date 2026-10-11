package org.techwithkaushik.formsnap.ai

import android.graphics.RectF

enum class DetectedClass(val id: Int, val label: String) {
    PHOTO(0, "Photo"),
    SIGNATURE(1, "Signature");

    companion object {
        fun fromId(id: Int): DetectedClass? = entries.firstOrNull { it.id == id }
    }
}

data class DetectedObject(
    val id: Long,
    val classId: Int,
    val label: String,
    val confidence: Float,
    val boundingBox: RectF,
) {
    /** All detected classes can be previewed and selected; the default form export
     * still treats class 0 as PHOTO and class 1 as SIGNATURE. */
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
)

data class DetectionConfig(
    val inputSize: Int = 320,
    val confidenceThreshold: Float = 0.35f,
    val iouThreshold: Float = 0.45f,
    val maxDetections: Int = 8,
    val classLabels: List<String> = emptyList(),
)

package org.techwithkaushik.formSnap.pipeline

/**
 * Image appearance controls shared by the output processor.
 *
 * Keep this data model independent of the removed interactive preview/editor UI.
 */
data class AppearanceAdjustments(
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val sharpness: Float = 0f,
    val denoise: Float = 0f,
    val inkThreshold: Int = 150,
)

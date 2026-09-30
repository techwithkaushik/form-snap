package org.techwithkaushik.formSnap.pipeline

object AppearanceTuning {
    fun clamp(adjustments: AppearanceAdjustments): AppearanceAdjustments =
        adjustments.copy(
            brightness = adjustments.brightness.coerceIn(-0.5f, 0.5f),
            contrast = adjustments.contrast.coerceIn(0.7f, 1.5f),
            saturation = adjustments.saturation.coerceIn(0.5f, 1.5f),
            sharpness = adjustments.sharpness.coerceIn(0f, 1f),
            denoise = adjustments.denoise.coerceIn(0f, 1f),
            inkThreshold = adjustments.inkThreshold.coerceIn(80, 220),
        )
}
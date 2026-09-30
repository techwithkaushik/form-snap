package org.techwithkaushik.formSnap.pipeline

object AppearanceTuning {
    fun defaults(kind: DetectionKind): AppearanceAdjustments = when (kind) {
        DetectionKind.PHOTO -> AppearanceAdjustments(
            brightness = 0f, contrast = 1f, saturation = 1f, sharpness = 0f, denoise = 0f,
            inkThreshold = 150,
        )
        DetectionKind.SIGNATURE -> AppearanceAdjustments(
            brightness = 0f, contrast = 1f, saturation = 1f, sharpness = 0f, denoise = 0f,
            inkThreshold = 150, backgroundCleanup = 0f,
        )
    }

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
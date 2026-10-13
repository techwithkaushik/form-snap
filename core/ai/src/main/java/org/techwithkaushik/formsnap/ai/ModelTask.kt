package org.techwithkaushik.formsnap.ai

/**
 * Conservative task classification from tensor contracts.
 *
 * This is a routing hint, not a guarantee that every model with a matching
 * shape uses the same preprocessing or output semantics. Metadata and model
 * documentation remain authoritative when available.
 */
enum class ModelTask {
    OBJECT_DETECTION,
    IMAGE_CLASSIFICATION,
    UNSUPPORTED,
}

object ModelTaskClassifier {
    fun classify(inputShape: IntArray, outputShape: IntArray): ModelTask {
        if (!isSingleImageRgbInput(inputShape)) return ModelTask.UNSUPPORTED

        if (YoloOutputShape.rawClassCount(outputShape, inputShape) != null ||
            YoloOutputShape.isLikelyNmsOutput(outputShape, inputShape)
        ) {
            return ModelTask.OBJECT_DETECTION
        }

        // Common classifier exports use [1, classes] or [1, 1, 1, classes].
        // Only classify these as likely classification; arbitrary output shapes
        // must not be guessed to avoid rendering nonsense predictions.
        val normalized = outputShape.dropWhile { it == 1 }
        val isClassification = outputShape.firstOrNull() == 1 &&
            normalized.size == 1 &&
            normalized[0] > 1
        return if (isClassification) ModelTask.IMAGE_CLASSIFICATION else ModelTask.UNSUPPORTED
    }

    private fun isSingleImageRgbInput(shape: IntArray): Boolean =
        shape.size == 4 &&
            shape[0] == 1 &&
            shape.all { it > 0 } &&
            (shape[3] == 3 || shape[1] == 3)
}

package org.techwithkaushik.formsnap.ai

/**
 * Conservative task classification from tensor contracts. Shape detection is
 * only a routing hint; model metadata remains authoritative for preprocessing.
 */
enum class ModelTask {
    OBJECT_DETECTION,
    IMAGE_CLASSIFICATION,
    SSD_OBJECT_DETECTION,
    UNSUPPORTED,
}

object ModelTaskClassifier {
    fun classify(inputShape: IntArray, outputShape: IntArray): ModelTask =
        classify(inputShape, listOf(outputShape))

    fun classify(inputShape: IntArray, outputShapes: List<IntArray>): ModelTask {
        if (!isSingleImageRgbInput(inputShape)) return ModelTask.UNSUPPORTED
        if (SsdOutputContract.isLikelyDetectionPostProcess(outputShapes)) {
            return ModelTask.SSD_OBJECT_DETECTION
        }
        if (outputShapes.size != 1) return ModelTask.UNSUPPORTED
        val outputShape = outputShapes.single()

        if (YoloOutputShape.rawClassCount(outputShape, inputShape) != null ||
            YoloOutputShape.isLikelyNmsOutput(outputShape, inputShape)
        ) {
            return ModelTask.OBJECT_DETECTION
        }

        // Common classifier exports use [1, classes] or [1, 1, 1, classes].
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

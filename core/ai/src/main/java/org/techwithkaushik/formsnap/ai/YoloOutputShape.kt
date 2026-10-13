package org.techwithkaushik.formsnap.ai

/**
 * Shared shape heuristics for common YOLO TFLite exports.
 *
 * Shape alone cannot perfectly distinguish every custom post-processing export,
 * but comparing small six-channel tensors against the input-size YOLO grids
 * prevents a common raw-output/NMS ambiguity.
 */
internal object YoloOutputShape {
    fun isLikelyNmsOutput(outputShape: IntArray, inputShape: IntArray): Boolean {
        if (outputShape.size != 3) return false
        val rows = outputShape[outputShape.lastIndex - 1]
        val columns = outputShape.last()
        if (columns !in 6..7 || rows !in 1..1000) return false

        val dimensions = when {
            inputShape.size == 4 && inputShape[3] == 3 -> inputShape[2] to inputShape[1]
            inputShape.size == 4 && inputShape[1] == 3 -> inputShape[3] to inputShape[2]
            else -> 0 to 0
        }
        return isLikelyNmsOutput(outputShape, dimensions.first, dimensions.second)
    }

    fun isLikelyNmsOutput(outputShape: IntArray, inputWidth: Int, inputHeight: Int): Boolean {
        if (outputShape.size != 3) return false
        val rows = outputShape[outputShape.lastIndex - 1]
        val columns = outputShape.last()
        if (columns !in 6..7 || rows !in 1..1000) return false
        if (inputWidth <= 0 || inputHeight <= 0) return true

        // YOLO has three prediction grids. Compute their candidate counts from
        // width and height independently so rectangular exports are recognized.
        fun gridCandidates(stride: Int): Int =
            ((inputWidth + stride - 1) / stride) * ((inputHeight + stride - 1) / stride)
        val expectedRawCandidates =
            gridCandidates(8) + gridCandidates(16) + gridCandidates(32)
        return rows != expectedRawCandidates
    }

    fun rawClassCount(outputShape: IntArray, inputShape: IntArray): Int? {
        if (outputShape.size != 3 || isLikelyNmsOutput(outputShape, inputShape)) return null
        val a = outputShape[outputShape.lastIndex - 1]
        val b = outputShape.last()
        val channels = when {
            a in 5..512 && b > a -> a
            b in 5..512 && a > b -> b
            else -> return null
        }
        return (channels - 4).takeIf { it in 1..1000 }
    }
}

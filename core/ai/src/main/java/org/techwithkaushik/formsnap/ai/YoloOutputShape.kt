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

        val inputSize = when {
            inputShape.size == 4 && inputShape[3] == 3 -> inputShape[1]
            inputShape.size == 4 && inputShape[1] == 3 -> inputShape[2]
            else -> 0
        }
        if (inputSize <= 0) return true

        val stride8 = (inputSize + 7) / 8
        val stride16 = (inputSize + 15) / 16
        val stride32 = (inputSize + 31) / 32
        val expectedRawCandidates = stride8 * stride8 + stride16 * stride16 + stride32 * stride32
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

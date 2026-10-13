package org.techwithkaushik.formsnap.ai

/**
 * Shape-contract helpers for the common TFLite Detection_PostProcess (SSD)
 * signature. Tensor order is commonly boxes [1,N,4], classes [1,N],
 * scores [1,N], count [1]. The canonical output order is required because classes
 *
 * This identifies a likely SSD signature only; it does not claim support for
 * arbitrary multi-output detectors or custom post-processing.
 */
internal object SsdOutputContract {
    fun isLikelyDetectionPostProcess(outputShapes: List<IntArray>): Boolean {
        if (outputShapes.size != 4) return false
        if (outputShapes.any { it.firstOrNull() != 1 }) return false
        val boxes = outputShapes[0]
        val classes = outputShapes[1]
        val scores = outputShapes[2]
        val count = outputShapes[3]
        if (boxes.size != 3 || boxes[2] != 4 || boxes[1] !in 1..1000) return false
        if (classes.size != 2 || scores.size != 2) return false
        if (classes[1] != boxes[1] || scores[1] != boxes[1]) return false
        return count.contentEquals(intArrayOf(1)) || count.contentEquals(intArrayOf(1, 1))
    }
}

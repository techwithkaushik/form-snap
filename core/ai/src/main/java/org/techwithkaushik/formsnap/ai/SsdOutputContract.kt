package org.techwithkaushik.formsnap.ai

/**
 * Shape-contract helpers for the common TFLite Detection_PostProcess (SSD)
 * signature. Tensor order is commonly boxes [1,N,4], classes [1,N],
 * scores [1,N], count [1], but outputs may be reordered by exporters.
 *
 * This identifies a likely SSD signature only; it does not claim support for
 * arbitrary multi-output detectors or custom post-processing.
 */
internal object SsdOutputContract {
    fun isLikelyDetectionPostProcess(outputShapes: List<IntArray>): Boolean {
        if (outputShapes.size != 4) return false
        val normalized = outputShapes.map { shape ->
            if (shape.firstOrNull() != 1) return false
            shape.drop(1)
        }
        val hasBoxes = normalized.any { it.size == 2 && it[1] == 4 && it[0] in 1..1000 }
        val hasVector = normalized.count { it.size == 1 && it[0] in 1..1000 } >= 2
        val hasCount = normalized.any { it.isEmpty() || (it.size == 1 && it[0] == 1) }
        return hasBoxes && hasVector && hasCount
    }
}

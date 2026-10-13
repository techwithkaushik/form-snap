package org.techwithkaushik.formsnap.ai

/**
 * Validates tensor dimensions before a model output is indexed by the YOLO decoder.
 * Supported YOLO output heads are rank-3 tensors such as [1, candidates, channels].
 */
internal object YoloTensorShape {
    fun expectedValueCount(shape: IntArray): Int? {
        if (shape.size != 3 || shape.any { it <= 0 }) return null

        var count = 1L
        for (dimension in shape) {
            // Check before multiplying so even three enormous dimensions
            // cannot overflow Long and wrap into a seemingly valid count.
            if (count > Int.MAX_VALUE.toLong() / dimension.toLong()) return null
            count *= dimension.toLong()
        }
        return count.toInt()
    }
}

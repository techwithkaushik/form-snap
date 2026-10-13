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
            count *= dimension.toLong()
            if (count > Int.MAX_VALUE) return null
        }
        return count.toInt()
    }
}

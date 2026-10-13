package org.techwithkaushik.formsnap.ai

/**
 * Converts one-dimensional classifier outputs into ranked confidence values.
 *
 * Bounded scores are preserved because they may be independent sigmoid
 * probabilities for a multi-label model. Values outside [0,1] are treated as
 * logits and normalized with a numerically stable softmax.
 */
internal object ClassificationScoreDecoder {
    fun topK(scores: FloatArray, topK: Int = 5): List<Pair<Int, Float>> {
        require(topK > 0) { "topK must be positive." }
        val finite = scores.indices.filter { scores[it].isFinite() }
        require(finite.isNotEmpty()) { "Classifier returned no finite class scores." }
        val bounded = finite.all { scores[it] in 0f..1f }
        val maxScore = finite.maxOf { scores[it] }
        val denominator = if (bounded) 1.0 else {
            finite.sumOf { kotlin.math.exp((scores[it] - maxScore).toDouble()) }
        }
        return finite.sortedByDescending { scores[it] }.take(topK.coerceAtMost(100)).map { id ->
            val confidence = if (bounded) scores[id].coerceIn(0f, 1f) else
                (kotlin.math.exp((scores[id] - maxScore).toDouble()) / denominator).toFloat()
            id to confidence
        }
    }
}

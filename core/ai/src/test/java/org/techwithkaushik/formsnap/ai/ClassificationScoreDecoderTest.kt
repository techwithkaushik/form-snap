package org.techwithkaushik.formsnap.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassificationScoreDecoderTest {
    @Test
    fun preservesIndependentProbabilitiesForMultiLabelModels() {
        val result = ClassificationScoreDecoder.topK(floatArrayOf(0.82f, 0.61f, 0.03f), 2)
        assertEquals(listOf(0, 1), result.map { it.first })
        assertEquals(0.82f, result[0].second, 0.0001f)
        assertEquals(0.61f, result[1].second, 0.0001f)
    }

    @Test
    fun appliesStableSoftmaxToLogits() {
        val result = ClassificationScoreDecoder.topK(floatArrayOf(1000f, 999f, -1000f), 3)
        assertEquals(3, result.size)
        assertTrue(result.all { it.second.isFinite() && it.second in 0f..1f })
        assertTrue(result[0].second > result[1].second)
        assertTrue(result[0].second + result[1].second > 0.999f)
    }

    @Test
    fun skipsNonFiniteScoresAndRespectsTopK() {
        val result = ClassificationScoreDecoder.topK(floatArrayOf(Float.NaN, 0.7f, Float.POSITIVE_INFINITY, 0.2f), 1)
        assertEquals(listOf(1), result.map { it.first })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAllNonFiniteScores() {
        ClassificationScoreDecoder.topK(floatArrayOf(Float.NaN, Float.NEGATIVE_INFINITY))
    }
}

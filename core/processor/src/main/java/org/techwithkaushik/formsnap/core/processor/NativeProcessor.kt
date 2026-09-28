package org.techwithkaushik.formsnap.core.processor

import android.graphics.RectF
import org.opencv.core.Mat

data class NativeDetection(
    val bounds: RectF,
    val confidence: Float,
    val kind: Int,
)

class NativeProcessor {
    init {
        System.loadLibrary("formsnap_processor")
    }

    fun detect(
        source: Mat,
        kind: Int,
        adaptiveBias: Double,
        blockSize: Int = 31,
        localC: Double = 8.0,
    ): NativeDetection? {
        require(!source.empty()) { "Source image is empty" }
        require(kind == 0 || kind == 1) { "Unsupported detection kind: $kind" }

        val safeBlock = blockSize.coerceIn(3, 99).let { if (it % 2 == 0) it + 1 else it }
        val handle = nativeDetect(
            source.nativeObjAddr,
            kind,
            adaptiveBias.coerceIn(-8.0, 8.0),
            safeBlock,
            localC.coerceIn(-32.0, 32.0),
        )
        if (handle == 0L) return null

        return try {
            val values = nativeReadResult(handle)
                ?: error("Native result buffer is unavailable.")
            require(values.size == 5) { "Native result buffer is invalid." }
            NativeDetection(
                bounds = RectF(
                    values[0].coerceAtLeast(0f),
                    values[1].coerceAtLeast(0f),
                    values[2].coerceAtLeast(1f),
                    values[3].coerceAtLeast(1f),
                ),
                confidence = values[4].coerceIn(0f, 1f),
                kind = kind,
            )
        } finally {
            nativeReleaseResult(handle)
        }
    }

    private external fun nativeDetect(
        bgrMatAddr: Long,
        kind: Int,
        bias: Double,
        blockSize: Int,
        localC: Double,
    ): Long

    private external fun nativeReadResult(resultAddr: Long): FloatArray?
    private external fun nativeReleaseResult(resultAddr: Long): Long
}

package org.techwithkaushik.formsnap.core.processor

import android.graphics.RectF
import org.opencv.core.Mat

data class NativeDetection(
    val bounds: RectF,
    val confidence: Float,
    val kind: Int
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
        localC: Double = 8.0
    ): NativeDetection? {
        require(!source.empty()) { "Source image is empty" }
        val handle = nativeDetect(
            source.nativeObjAddr,
            kind,
            adaptiveBias.coerceIn(-8.0, 8.0),
            if (blockSize >= 3) blockSize or 1 else 3,
            localC.coerceIn(-32.0, 32.0)
        )
        if (handle == 0L) return null
        return try {
            val values = nativeReadResult(handle)
            NativeDetection(
                bounds = RectF(values[0], values[1], values[2], values[3]),
                confidence = values[4].coerceIn(0f, 1f),
                kind = kind
            )
        } finally {
            nativeReleaseResult(handle)
        }
    }

    private external fun nativeReadResult(resultAddr: Long): FloatArray
    private external fun nativeDetect(
        bgrMatAddr: Long,
        kind: Int,
        bias: Double,
        blockSize: Int,
        localC: Double
    ): Long
    private external fun nativeReleaseResult(resultAddr: Long): Long
}

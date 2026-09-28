package org.techwithkaushik.formsnap.processor

actual class ImageProcessor {
    private val nativeHandle: Long

    init {
        System.loadLibrary("formsnap_processor")
        nativeHandle = 0L
    }

    actual fun processForm(
        imageData: ByteArray,
        adaptiveBlockSize: Int,
        adaptiveConstant: Double,
    ): ByteArray {
        require(imageData.isNotEmpty()) { "Image data is empty." }
        val safeBlock = adaptiveBlockSize.coerceIn(3, 99).let {
            if (it % 2 == 0) it + 1 else it
        }
        return processNativeForm(imageData, safeBlock, adaptiveConstant.coerceIn(-32.0, 32.0))
    }

    private external fun processNativeForm(
        imageData: ByteArray,
        blockSize: Int,
        constant: Double,
    ): ByteArray
}

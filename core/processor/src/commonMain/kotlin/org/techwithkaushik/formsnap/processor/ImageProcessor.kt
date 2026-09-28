package org.techwithkaushik.formsnap.processor

expect class ImageProcessor {
    fun processForm(
        imageData: ByteArray,
        adaptiveBlockSize: Int = 31,
        adaptiveConstant: Double = 8.0,
    ): ByteArray
}

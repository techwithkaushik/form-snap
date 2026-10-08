package org.techwithkaushik.formSnap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Re-encodes a processed image to the user's size budget. It first reduces JPEG
 * quality in small steps, then downsamples only when required by the hard limit.
 * The source file is never modified.
 */
internal object SavedImageEncoder {
    fun encodeWithinLimit(file: File, maxKb: Int): ByteArray {
        require(file.isFile && file.length() > 0L) { "Processed image is missing or empty." }
        require(maxKb in 5..2048) { "Output size limit must be between 5 and 2048 KB." }
        val limit = maxKb * 1024
        val decoded = BitmapFactory.decodeFile(file.absolutePath)
            ?: throw IOException("Unable to decode the processed image.")
        var current = decoded
        try {
            for (scaleStep in 0..MAX_SCALE_STEPS) {
                val encoded = bestJpegUnderLimit(current, limit)
                if (encoded != null) return encoded

                if (scaleStep == MAX_SCALE_STEPS) break
                val nextWidth = (current.width * SCALE_FACTOR).roundToInt().coerceAtLeast(1)
                val nextHeight = (current.height * SCALE_FACTOR).roundToInt().coerceAtLeast(1)
                if (nextWidth < MIN_DIMENSION || nextHeight < MIN_DIMENSION ||
                    (nextWidth == current.width && nextHeight == current.height)
                ) break
                val resized = Bitmap.createScaledBitmap(current, nextWidth, nextHeight, true)
                if (current !== decoded) current.recycle()
                current = resized
            }
        } finally {
            if (current !== decoded) current.recycle()
            decoded.recycle()
        }
        throw IOException(
            "Could not fit the image within $maxKb KB without reducing it below the safe minimum size. Increase the size limit and try again.",
        )
    }

    /**
     * Finds the highest JPEG quality that fits the hard byte limit.
     * Binary search avoids the old coarse quality ladder and preserves more
     * detail at the same file size.
     */
    private fun bestJpegUnderLimit(bitmap: Bitmap, limit: Int): ByteArray? {
        var low = MIN_JPEG_QUALITY
        var high = MAX_JPEG_QUALITY
        var best: ByteArray? = null

        while (low <= high) {
            val quality = (low + high) ushr 1
            val bytes = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bytes)) {
                throw IOException("JPEG encoder could not encode the output.")
            }
            val encoded = bytes.toByteArray()
            if (encoded.size <= limit) {
                best = encoded
                low = quality + 1
            } else {
                high = quality - 1
            }
        }
        return best
    }

    /**
     * PNG is the default signature format to preserve binary/anti-aliased ink
     * without JPEG ringing. If the size budget is exceeded, only dimensions
     * are reduced; PNG encoding itself remains lossless.
     */
    fun encodePngWithinLimit(file: File, maxKb: Int): ByteArray {
        require(file.isFile && file.length() > 0L) { "Processed image is missing or empty." }
        require(maxKb in 5..2048) { "Output size limit must be between 5 and 2048 KB." }
        val limit = maxKb * 1024
        val decoded = BitmapFactory.decodeFile(file.absolutePath)
            ?: throw IOException("Unable to decode the processed signature.")
        var current = decoded
        try {
            for (scaleStep in 0..MAX_SCALE_STEPS) {
                val bytes = ByteArrayOutputStream()
                if (!current.compress(Bitmap.CompressFormat.PNG, 100, bytes)) {
                    throw IOException("PNG encoder could not encode the signature.")
                }
                val encoded = bytes.toByteArray()
                if (encoded.size <= limit) return encoded
                if (scaleStep == MAX_SCALE_STEPS) break
                val nextWidth = (current.width * SCALE_FACTOR).roundToInt().coerceAtLeast(1)
                val nextHeight = (current.height * SCALE_FACTOR).roundToInt().coerceAtLeast(1)
                if (nextWidth < MIN_DIMENSION || nextHeight < MIN_DIMENSION ||
                    (nextWidth == current.width && nextHeight == current.height)
                ) break
                val resized = Bitmap.createScaledBitmap(current, nextWidth, nextHeight, true)
                if (current !== decoded) current.recycle()
                current = resized
            }
        } finally {
            if (current !== decoded) current.recycle()
            decoded.recycle()
        }
        throw IOException(
            "Could not fit the signature within ${maxKb} KB without reducing it below the safe minimum size.",
        )
    }

    private val JPEG_QUALITIES = intArrayOf(95, 90, 85, 80, 75, 70, 65, 60)
    private const val SCALE_FACTOR = 0.90
    private const val MAX_SCALE_STEPS = 12
    private const val MIN_DIMENSION = 120
}

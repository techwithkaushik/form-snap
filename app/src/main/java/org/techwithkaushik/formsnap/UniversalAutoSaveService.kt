package org.techwithkaushik.formSnap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.techwithkaushik.formsnap.ai.AiModelManager
import org.techwithkaushik.formsnap.ai.DetectionConfig
import org.techwithkaushik.formsnap.ai.YoloV8TfliteDetector
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

enum class OutputImageType(val extension: String, val mimeType: String, val format: Bitmap.CompressFormat) {
    JPEG("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG),
    PNG("png", "image/png", Bitmap.CompressFormat.PNG),
    WEBP("webp", "image/webp", Bitmap.CompressFormat.WEBP)
}

data class UniversalSavedDetection(
    val className: String,
    val classId: Int,
    val confidence: Float,
    val relativePath: String,
    val byteCount: Int,
)

object UniversalAutoSaveService {
    fun processAndSave(
        context: Context,
        input: File,
        outputWidthMm: Double,
        outputHeightMm: Double,
        dpi: Int,
        maxKb: Int,
        imageType: OutputImageType,
    ): List<UniversalSavedDetection> {
        require(input.isFile && input.length() > 0L) { "Input image is missing or empty." }
        require(outputWidthMm > 0.0 && outputHeightMm > 0.0 && outputWidthMm.isFinite() && outputHeightMm.isFinite()) {
            "Output width and height must be positive."
        }
        require(dpi in 72..1200) { "DPI must be between 72 and 1200." }
        require(maxKb in 5..2048) { "Maximum output size must be between 5 and 2048 KB." }

        val manager = AiModelManager(context)
        val model = manager.activeModelFile()
            ?: throw IOException("Import and activate a TFLite object-detection model first.")
        val decoded = BitmapFactory.decodeFile(input.absolutePath)
            ?: throw IOException("Unable to decode input image.")
        require(decoded.width.toLong() * decoded.height <= MAX_SOURCE_PIXELS) {
            decoded.recycle()
            "Image is too large to process safely. Import a smaller image or capture at a lower resolution."
        }

        val threshold = context.getSharedPreferences("formsnap.live.detection", Context.MODE_PRIVATE)
            .getFloat("confidence_threshold", 0.35f).coerceIn(0.05f, 0.95f)
        val widthPx = (outputWidthMm * dpi / 25.4).roundToInt().coerceAtLeast(1)
        val heightPx = (outputHeightMm * dpi / 25.4).roundToInt().coerceAtLeast(1)
        require(widthPx.toLong() * heightPx <= MAX_OUTPUT_PIXELS) { "Requested output dimensions are too large." }

        try {
            val detections = YoloV8TfliteDetector(
                context,
                modelFile = model,
                config = DetectionConfig(
                    confidenceThreshold = threshold,
                    iouThreshold = 0.45f,
                    maxDetections = 50,
                    classLabels = manager.classLabels(model),
                ),
            ).use { detector -> detector.detect(decoded) }

            if (detections.isEmpty()) {
                throw IOException("No objects passed the ${(threshold * 100).roundToInt()}% confidence threshold. Check model classes, labels, lighting, or lower the threshold.")
            }

            val store = AutoSaveStore(context)
            val outputs = ArrayList<UniversalSavedDetection>(detections.size)
            detections.forEach { detection ->
                val box = detection.boundingBox
                val left = box.left.roundToInt().coerceIn(0, decoded.width - 1)
                val top = box.top.roundToInt().coerceIn(0, decoded.height - 1)
                val right = box.right.roundToInt().coerceIn(left + 1, decoded.width)
                val bottom = box.bottom.roundToInt().coerceIn(top + 1, decoded.height)
                val crop = Bitmap.createBitmap(decoded, left, top, right - left, bottom - top)
                try {
                    val resized = Bitmap.createScaledBitmap(crop, widthPx, heightPx, true)
                    try {
                        val bytes = UniversalImageEncoder.encode(resized, maxKb, imageType)
                        val relativePath = store.saveDetection(
                            model.name,
                            detection.label,
                            bytes,
                            imageType.extension,
                            imageType.mimeType,
                        )
                        outputs += UniversalSavedDetection(
                            className = detection.label,
                            classId = detection.classId,
                            confidence = detection.confidence,
                            relativePath = relativePath,
                            byteCount = bytes.size,
                        )
                    } finally {
                        if (resized !== crop) resized.recycle()
                    }
                } finally {
                    crop.recycle()
                }
            }
            return outputs
        } finally {
            decoded.recycle()
        }
    }

    private const val MAX_SOURCE_PIXELS = 24_000_000L
    private const val MAX_OUTPUT_PIXELS = 12_000_000L
}

private object UniversalImageEncoder {
    fun encode(bitmap: Bitmap, maxKb: Int, type: OutputImageType): ByteArray {
        val limit = maxKb * 1024
        var current = bitmap
        var owned: Bitmap? = null
        try {
            repeat(14) {
                val qualities = if (type == OutputImageType.JPEG || type == OutputImageType.WEBP) (95 downTo 25 step 5).toList() else listOf(100)
                for (quality in qualities) {
                    val stream = ByteArrayOutputStream()
                    val ok = current.compress(type.format, quality, stream)
                    if (!ok) throw IOException("Image encoder failed for ${type.extension}.")
                    val bytes = stream.toByteArray()
                    if (bytes.size <= limit) return bytes
                }
                val nextWidth = (current.width * 0.85f).roundToInt().coerceAtLeast(1)
                val nextHeight = (current.height * 0.85f).roundToInt().coerceAtLeast(1)
                if (nextWidth < 64 || nextHeight < 64 || (nextWidth == current.width && nextHeight == current.height)) return@repeat
                val scaled = Bitmap.createScaledBitmap(current, nextWidth, nextHeight, true)
                owned?.recycle()
                owned = scaled
                current = scaled
            }
            val stream = ByteArrayOutputStream()
            if (!current.compress(type.format, 20, stream) || stream.size() > limit) {
                throw IOException("Could not fit output within ${maxKb} KB. Increase Max KB or reduce output dimensions.")
            }
            return stream.toByteArray()
        } finally {
            owned?.recycle()
        }
    }
}

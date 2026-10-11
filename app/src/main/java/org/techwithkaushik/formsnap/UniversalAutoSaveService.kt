package org.techwithkaushik.formSnap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import org.techwithkaushik.formsnap.ai.AiModelManager
import org.techwithkaushik.formsnap.ai.DetectionConfig
import org.techwithkaushik.formsnap.ai.YoloV8TfliteDetector
import org.techwithkaushik.formsnap.feature.capture.LiveDetection
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class OutputImageType(val extension: String, val mimeType: String, val format: Bitmap.CompressFormat) {
    JPEG("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG),
    PNG("png", "image/png", Bitmap.CompressFormat.PNG),
    WEBP("webp", "image/webp", Bitmap.CompressFormat.WEBP),
}
enum class MergeOrientation { VERTICAL, HORIZONTAL }

data class UniversalSavedDetection(val className: String, val classId: Int, val confidence: Float, val relativePath: String, val byteCount: Int)
private data class CropCandidate(val label: String, val classId: Int, val confidence: Float, val bounds: Rect)

object UniversalAutoSaveService {
    fun processAndSave(
        context: Context,
        input: File,
        maxKb: Int,
        imageType: OutputImageType,
        selectedDetections: List<LiveDetection> = emptyList(),
        autoSaveEnabled: Boolean = true,
        mergeEnabled: Boolean = false,
        mergeOrientation: MergeOrientation = MergeOrientation.VERTICAL,
    ): List<UniversalSavedDetection> {
        require(input.isFile && input.length() > 0L) { "Input image is missing or empty." }
        require(maxKb in 5..2048) { "Maximum output size must be between 5 and 2048 KB." }
        if (!autoSaveEnabled && selectedDetections.isEmpty()) throw IOException("Manual mode: tap one or more detection boxes, then press the shutter.")
        val manager = AiModelManager(context)
        val model = manager.activeModelFile() ?: throw IOException("Import and activate a TFLite object-detection model first.")
        val source = BitmapFactory.decodeFile(input.absolutePath) ?: throw IOException("Unable to decode input image.")
        if (source.width.toLong() * source.height > MAX_SOURCE_PIXELS) {
            source.recycle()
            throw IOException("Image is too large to process safely.")
        }
        try {
            val candidates = if (selectedDetections.isNotEmpty()) {
                selectedDetections.mapNotNull { item ->
                    val left = item.sourceLeft ?: item.left
                    val top = item.sourceTop ?: item.top
                    val right = item.sourceRight ?: item.right
                    val bottom = item.sourceBottom ?: item.bottom
                    candidate(item.label, -1, item.confidence, RectF(left * source.width, top * source.height, right * source.width, bottom * source.height), source.width, source.height)
                }
            } else {
                val threshold = context.getSharedPreferences("formsnap.live.detection", Context.MODE_PRIVATE).getFloat("confidence_threshold", 0.35f).coerceIn(0.05f, 0.95f)
                YoloV8TfliteDetector(
                    context,
                    modelFile = model,
                    config = DetectionConfig(confidenceThreshold = threshold, iouThreshold = 0.45f, maxDetections = 50, classLabels = manager.classLabels(model)),
                ).use { detector ->
                    detector.detect(source).mapNotNull { item ->
                        candidate(item.label, item.classId, item.confidence, item.boundingBox, source.width, source.height)
                    }
                }
            }
            if (candidates.isEmpty()) throw IOException("No objects to save. Check confidence or select a box in manual mode.")
            val store = AutoSaveStore(context)
            if (mergeEnabled && candidates.size > 1) {
                val merged = mergeCrops(source, candidates, mergeOrientation)
                try {
                    val bytes = UniversalImageEncoder.encode(merged, maxKb, imageType)
                    val folder = if (mergeOrientation == MergeOrientation.VERTICAL) "Merged_Vertical" else "Merged_Horizontal"
                    val path = store.saveDetection(model.name, folder, bytes, imageType.extension, imageType.mimeType)
                    return listOf(UniversalSavedDetection(folder, -1, candidates.minOf { it.confidence }, path, bytes.size))
                } finally { merged.recycle() }
            }
            return candidates.map { item ->
                val crop = Bitmap.createBitmap(source, item.bounds.left, item.bounds.top, item.bounds.width(), item.bounds.height())
                try {
                    val bytes = UniversalImageEncoder.encode(crop, maxKb, imageType)
                    val path = store.saveDetection(model.name, item.label, bytes, imageType.extension, imageType.mimeType)
                    UniversalSavedDetection(item.label, item.classId, item.confidence, path, bytes.size)
                } finally { crop.recycle() }
            }
        } finally { source.recycle() }
    }

    private fun candidate(label: String, classId: Int, confidence: Float, box: RectF, imageWidth: Int, imageHeight: Int): CropCandidate? {
        val left = box.left.roundToInt().coerceIn(0, imageWidth - 1)
        val top = box.top.roundToInt().coerceIn(0, imageHeight - 1)
        val right = box.right.roundToInt().coerceIn(left + 1, imageWidth)
        val bottom = box.bottom.roundToInt().coerceIn(top + 1, imageHeight)
        if (right <= left || bottom <= top) return null
        return CropCandidate(label, classId, confidence, Rect(left, top, right, bottom))
    }

    private fun mergeCrops(source: Bitmap, candidates: List<CropCandidate>, orientation: MergeOrientation): Bitmap {
        val align = if (orientation == MergeOrientation.VERTICAL) candidates.maxOf { it.bounds.width() } else candidates.maxOf { it.bounds.height() }
        val dimensions = candidates.map { item ->
            val scale = align.toFloat() / if (orientation == MergeOrientation.VERTICAL) item.bounds.width() else item.bounds.height()
            (item.bounds.width() * scale).roundToInt().coerceAtLeast(1) to (item.bounds.height() * scale).roundToInt().coerceAtLeast(1)
        }
        val naturalWidth = if (orientation == MergeOrientation.VERTICAL) align else dimensions.sumOf { it.first }
        val naturalHeight = if (orientation == MergeOrientation.VERTICAL) dimensions.sumOf { it.second } else align
        val pixels = (naturalWidth.toLong() * naturalHeight).coerceAtLeast(1L)
        val shrink = min(1f, sqrt(MAX_MERGED_PIXELS.toFloat() / pixels.toFloat()))
        val result = Bitmap.createBitmap((naturalWidth * shrink).roundToInt().coerceAtLeast(1), (naturalHeight * shrink).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        var offset = 0
        candidates.forEachIndexed { index, item ->
            val crop = Bitmap.createBitmap(source, item.bounds.left, item.bounds.top, item.bounds.width(), item.bounds.height())
            try {
                val w = (dimensions[index].first * shrink).roundToInt().coerceAtLeast(1)
                val h = (dimensions[index].second * shrink).roundToInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(crop, w, h, true)
                try {
                    if (orientation == MergeOrientation.VERTICAL) { canvas.drawBitmap(scaled, 0f, offset.toFloat(), paint); offset += h }
                    else { canvas.drawBitmap(scaled, offset.toFloat(), 0f, paint); offset += w }
                } finally { if (scaled !== crop) scaled.recycle() }
            } finally { crop.recycle() }
        }
        return result
    }
    private const val MAX_SOURCE_PIXELS = 24_000_000L
    private const val MAX_MERGED_PIXELS = 12_000_000L
}

private object UniversalImageEncoder {
    fun encode(bitmap: Bitmap, maxKb: Int, type: OutputImageType): ByteArray {
        val limit = maxKb * 1024
        var current = bitmap
        var owned: Bitmap? = null
        try {
            for (step in 0..16) {
                val qualities = if (type == OutputImageType.JPEG || type == OutputImageType.WEBP) (95 downTo 25 step 5) else listOf(100)
                for (quality in qualities) {
                    val stream = ByteArrayOutputStream()
                    if (!current.compress(type.format, quality, stream)) throw IOException("Image encoder failed for ${type.extension}.")
                    val bytes = stream.toByteArray()
                    if (bytes.size <= limit) return bytes
                }
                if (step == 16) break
                val w = (current.width * 0.82f).roundToInt().coerceAtLeast(1)
                val h = (current.height * 0.82f).roundToInt().coerceAtLeast(1)
                if (w < 32 || h < 32 || (w == current.width && h == current.height)) break
                val scaled = Bitmap.createScaledBitmap(current, w, h, true)
                owned?.recycle()
                owned = scaled
                current = scaled
            }
            throw IOException("Could not fit output within ${maxKb} KB. Increase the KB limit.")
        } finally { owned?.recycle() }
    }
}

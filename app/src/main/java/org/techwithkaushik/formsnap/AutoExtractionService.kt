package org.techwithkaushik.formSnap

import android.content.Context
import android.graphics.BitmapFactory
import org.techwithkaushik.formsnap.ai.DetectedClass
import org.techwithkaushik.formsnap.ai.YoloV8TfliteDetector
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

data class AutoExtractionResult(
    val photoBytes: ByteArray?,
    val signatureBytes: ByteArray?,
    val detectorVersion: String,
    val photoConfidence: Float?,
    val signatureConfidence: Float?,
)

object AutoExtractionService {
    fun process(
        context: Context,
        input: File,
        dpi: Int,
        maxKb: Int,
        mode: String,
    ): AutoExtractionResult {
        require(input.isFile && input.length() > 0L) { "Input image is missing or empty." }

        val source = Imgcodecs.imread(input.absolutePath)
        require(!source.empty()) { "Unable to decode input image." }

        return try {
            val bitmap = BitmapFactory.decodeFile(
                input.absolutePath,
                BitmapFactory.Options().apply {
                    // AI only needs a compact inference bitmap. Final crops always
                    // come from the original OpenCV image, not this bitmap.
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                },
            ) ?: error("Unable to decode input image for AI detection.")

            try {
                val aiDetector = YoloV8TfliteDetector(context)
                val detectionResult = if (aiDetector.modelAvailable()) {
                    try {
                        val objects = aiDetector.detect(bitmap)
                        val photo = objects
                            .filter { it.classId == DetectedClass.PHOTO.id && it.isExtractable }
                            .maxByOrNull { it.confidence }
                        val signature = objects
                            .filter { it.classId == DetectedClass.SIGNATURE.id && it.isExtractable }
                            .maxByOrNull { it.confidence }

                        DetectionResult(
                            sourceWidth = source.cols(),
                            sourceHeight = source.rows(),
                            photo = photo?.let {
                                DetectionCandidate(
                                    kind = DetectionKind.PHOTO,
                                    bounds = it.boundingBox,
                                    confidence = it.confidence,
                                    source = "yolov8n-int8",
                                )
                            },
                            signature = signature?.let {
                                DetectionCandidate(
                                    kind = DetectionKind.SIGNATURE,
                                    bounds = it.boundingBox,
                                    confidence = it.confidence,
                                    source = "yolov8n-int8",
                                )
                            },
                            detectorVersion = "yolov8n-int8",
                        )
                    } finally {
                        aiDetector.close()
                    }
                } else {
                    // Keep the automatic workflow usable until the trained model
                    // asset is added. Once the real TFLite model is present, AI is
                    // always preferred and this fallback is not used.
                    UniversalDetectionEngine.detect(source)
                }

                UniversalPipelineBatch.process(source, detectionResult, dpi).use { processed ->
                    val tempSession = org.techwithkaushik.formSnap.foundation.ProcessingSession.create(context)
                    try {
                        val photoBytes = if (mode != "SIGNATURE" && processed.photo != null) {
                            val file = tempSession.file("photo.jpg")
                            check(Imgcodecs.imwrite(file.absolutePath, processed.photo.image)) {
                                "Unable to encode photo output."
                            }
                            SavedImageEncoder.encodeWithinLimit(file, maxKb)
                        } else null

                        val signatureBytes = if (mode != "PHOTO" && processed.signature != null) {
                            val file = tempSession.file("signature.jpg")
                            check(Imgcodecs.imwrite(file.absolutePath, processed.signature.image)) {
                                "Unable to encode signature output."
                            }
                            SavedImageEncoder.encodeWithinLimit(file, maxKb)
                        } else null

                        AutoExtractionResult(
                            photoBytes = photoBytes,
                            signatureBytes = signatureBytes,
                            detectorVersion = detectionResult.detectorVersion,
                            photoConfidence = detectionResult.photo?.confidence,
                            signatureConfidence = detectionResult.signature?.confidence,
                        )
                    } finally {
                        tempSession.closeAndDelete()
                    }
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            source.release()
        }
    }
}

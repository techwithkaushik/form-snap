package org.techwithkaushik.formSnap

import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.Utils
import org.techwithkaushik.formsnap.ai.DetectedClass
import org.techwithkaushik.formsnap.ai.YoloV8TfliteDetector
import org.techwithkaushik.formSnap.pipeline.DetectionCandidate
import org.techwithkaushik.formSnap.pipeline.DetectionKind
import org.techwithkaushik.formSnap.pipeline.DetectionResult
import org.techwithkaushik.formSnap.pipeline.PerspectiveRectifier
import org.techwithkaushik.formSnap.pipeline.UniversalDetectionEngine
import org.techwithkaushik.formSnap.pipeline.UniversalPipelineBatch
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
        photoWidthMm: Double = 40.0,
        photoHeightMm: Double = 50.0,
        signatureWidthMm: Double = 50.0,
        signatureHeightMm: Double = 20.0,
    ): AutoExtractionResult {
        require(input.isFile && input.length() > 0L) { "Input image is missing or empty." }

        val source = Imgcodecs.imread(input.absolutePath)
        require(!source.empty()) { "Unable to decode input image." }

        val rectified = PerspectiveRectifier.rectify(source)
        val workingSource = rectified.image

        return try {
            val aiBitmap = Bitmap.createBitmap(
                workingSource.cols(),
                workingSource.rows(),
                Bitmap.Config.RGB_565,
            )
            try {
                Utils.matToBitmap(workingSource, aiBitmap)

                val aiDetector = YoloV8TfliteDetector(context)
                val detectionResult = if (aiDetector.modelAvailable()) {
                    try {
                        val objects = aiDetector.detect(aiBitmap)
                        val photo = objects
                            .filter { it.classId == DetectedClass.PHOTO.id && it.isExtractable }
                            .maxByOrNull { it.confidence }
                        val signature = objects
                            .filter { it.classId == DetectedClass.SIGNATURE.id && it.isExtractable }
                            .maxByOrNull { it.confidence }

                        DetectionResult(
                            sourceWidth = workingSource.cols(),
                            sourceHeight = workingSource.rows(),
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
                    // asset is added. The trained model will be preferred automatically.
                    UniversalDetectionEngine.detect(workingSource)
                }

                UniversalPipelineBatch.process(
                    source = workingSource,
                    detection = detectionResult,
                    dpi = dpi,
                    photoWidthMm = photoWidthMm,
                    photoHeightMm = photoHeightMm,
                    signatureWidthMm = signatureWidthMm,
                    signatureHeightMm = signatureHeightMm,
                ).use { processed ->
                    val tempSession =
                        org.techwithkaushik.formSnap.foundation.ProcessingSession.create(context)
                    try {
                        val photoBytes = if (mode != "SIGNATURE" && processed.photo != null) {
                            val file = tempSession.file("photo.jpg")
                            check(Imgcodecs.imwrite(file.absolutePath, processed.photo.image)) {
                                "Unable to encode photo output."
                            }
                            SavedImageEncoder.encodeWithinLimit(file, maxKb)
                        } else null

                        val signatureBytes =
                            if (mode != "PHOTO" && processed.signature != null) {
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
                aiBitmap.recycle()
            }
        } finally {
            if (rectified.changed) workingSource.release()
            source.release()
        }
    }
}

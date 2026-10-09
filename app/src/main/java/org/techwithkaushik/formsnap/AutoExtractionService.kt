package org.techwithkaushik.formSnap

import android.content.Context
import android.graphics.Bitmap
import org.opencv.imgcodecs.Imgcodecs
import org.techwithkaushik.formSnap.pipeline.DetectionCandidate
import org.techwithkaushik.formSnap.pipeline.DetectionKind
import org.techwithkaushik.formSnap.pipeline.DetectionResult
import org.techwithkaushik.formSnap.pipeline.UniversalDetectionEngine
import org.techwithkaushik.formSnap.pipeline.UniversalPipelineBatch
import org.techwithkaushik.formsnap.ai.AiModelManager
import org.techwithkaushik.formsnap.ai.DetectedClass
import org.techwithkaushik.formsnap.ai.DetectionConfig
import org.techwithkaushik.formsnap.ai.YoloV8TfliteDetector
import org.techwithkaushik.formsnap.feature.capture.LiveDetection
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
        selectedDetections: List<LiveDetection> = emptyList(),
        photoWidthMm: Double = 40.0,
        photoHeightMm: Double = 50.0,
        signatureWidthMm: Double = 50.0,
        signatureHeightMm: Double = 20.0,
    ): AutoExtractionResult {
        require(input.isFile && input.length() > 0L) { "Input image is missing or empty." }

        val source = Imgcodecs.imread(input.absolutePath)
        require(!source.empty()) { "Unable to decode input image." }

        return try {
            val aiBitmap = Bitmap.createBitmap(
                source.cols(),
                source.rows(),
                Bitmap.Config.RGB_565,
            )
            try {
                org.opencv.android.Utils.matToBitmap(source, aiBitmap)

                val detectionResult = if (selectedDetections.isNotEmpty()) {
                    // A locked live selection is authoritative. Its normalized
                    // coordinates are remapped onto the original full-resolution
                    // capture, so the final crop never comes from the AI preview.
                    detectionFromLockedSelections(
                        selectedDetections = selectedDetections,
                        sourceWidth = source.cols(),
                        sourceHeight = source.rows(),
                    )
                } else {
                    val activeModel = AiModelManager(context).activeModelFile()
                    val aiDetector = if (activeModel != null) YoloV8TfliteDetector(context, modelFile = activeModel, config = DetectionConfig(maxClassId = 2)) else YoloV8TfliteDetector(context)
                    try {
                        if (aiDetector.modelAvailable()) {
                            val objects = aiDetector.detect(aiBitmap)
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
                                        source = activeModel?.name ?: "bundled-model",
                                    )
                                },
                                signature = signature?.let {
                                    DetectionCandidate(
                                        kind = DetectionKind.SIGNATURE,
                                        bounds = it.boundingBox,
                                        confidence = it.confidence,
                                        source = activeModel?.name ?: "bundled-model",
                                    )
                                },
                                detectorVersion = activeModel?.name ?: "bundled-model",
                            )
                        } else {
                            // Keep the classical fallback until the trained
                            // model asset is added; YOLO remains preferred.
                            UniversalDetectionEngine.detect(source)
                        }
                    } finally {
                        aiDetector.close()
                    }
                }

                UniversalPipelineBatch.process(
                    source = source,
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
                        } else {
                            null
                        }

                        val signatureBytes =
                            if (mode != "PHOTO" && processed.signature != null) {
                                val file = tempSession.file("signature.jpg")
                                check(Imgcodecs.imwrite(file.absolutePath, processed.signature.image)) {
                                    "Unable to encode signature output."
                                }
                                SavedImageEncoder.encodeWithinLimit(file, maxKb)
                            } else {
                                null
                            }

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
            source.release()
        }
    }

    private fun detectionFromLockedSelections(
        selectedDetections: List<LiveDetection>,
        sourceWidth: Int,
        sourceHeight: Int,
    ): DetectionResult {
        fun candidate(
            detection: LiveDetection,
            kind: DetectionKind,
        ): DetectionCandidate {
            val left = (detection.left.coerceIn(0f, 1f) * sourceWidth)
                .coerceIn(0f, sourceWidth - 1f)
            val top = (detection.top.coerceIn(0f, 1f) * sourceHeight)
                .coerceIn(0f, sourceHeight - 1f)
            val right = (detection.right.coerceIn(0f, 1f) * sourceWidth)
                .coerceIn(left + 1f, sourceWidth.toFloat())
            val bottom = (detection.bottom.coerceIn(0f, 1f) * sourceHeight)
                .coerceIn(top + 1f, sourceHeight.toFloat())

            return DetectionCandidate(
                kind = kind,
                bounds = android.graphics.RectF(left, top, right, bottom),
                confidence = detection.confidence,
                source = "live-lock",
            )
        }

        val photo = selectedDetections
            .filter { it.locked && it.label.equals("Photo", ignoreCase = true) }
            .minByOrNull { it.selectionIndex ?: Int.MAX_VALUE }
            ?.let { candidate(it, DetectionKind.PHOTO) }

        val signature = selectedDetections
            .filter { it.locked && it.label.equals("Signature", ignoreCase = true) }
            .minByOrNull { it.selectionIndex ?: Int.MAX_VALUE }
            ?.let { candidate(it, DetectionKind.SIGNATURE) }

        return DetectionResult(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            photo = photo,
            signature = signature,
            detectorVersion = "live-lock-v1",
        )
    }
}

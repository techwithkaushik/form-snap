package org.techwithkaushik.formsnap.feature.pipeline

import android.graphics.RectF
import kotlinx.coroutines.flow.StateFlow
import java.io.File

enum class DetectionKind { PHOTO, SIGNATURE }

enum class PipelineAction { REJECT, EDIT, ACCEPT }

data class DetectionBounds(
    val kind: DetectionKind,
    val estimated: RectF,
    val confidence: Float,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val hasPrintedFrame: Boolean,
)

data class PipelineState(
    val input: File? = null,
    val photo: DetectionBounds? = null,
    val signature: DetectionBounds? = null,
    val photoOutputPath: String? = null,
    val signatureOutputPath: String? = null,
    val processing: Boolean = false,
    val error: String? = null,
    val revision: Long = 0L,
)

interface PipelineEngine {
    val state: StateFlow<PipelineState>

    suspend fun process(
        input: File,
        dpi: Int,
        photoWidthMm: Double,
        photoHeightMm: Double,
        signatureWidthMm: Double,
        signatureHeightMm: Double,
    )

    suspend fun reprocess()
    suspend fun reject(kind: DetectionKind)
    suspend fun accept(kind: DetectionKind)
    suspend fun applyEditedResult(kind: DetectionKind, editedFile: File)
    fun close()
}
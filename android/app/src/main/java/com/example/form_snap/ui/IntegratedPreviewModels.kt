package org.techwithkaushik.formSnap.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.techwithkaushik.formSnap.pipeline.DetectionKind
import org.techwithkaushik.formSnap.pipeline.PreviewCorrectionState
import java.io.File

data class IntegratedPreviewItem(
    val kind: DetectionKind,
    val bounds: android.graphics.RectF,
    val preview: Bitmap?,
    val outputPath: String?,
)

object IntegratedPreviewMapper {
    fun loadBitmap(path: String?): Bitmap? {
        if (path == null) return null
        val file = File(path)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun item(
        kind: DetectionKind,
        state: PreviewCorrectionState?,
        previewPath: String?,
    ): IntegratedPreviewItem? {
        if (state == null) return null
        return IntegratedPreviewItem(
            kind = kind,
            bounds = android.graphics.RectF(state.currentBounds),
            preview = loadBitmap(previewPath),
            outputPath = previewPath,
        )
    }
}
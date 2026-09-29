package org.techwithkaushik.formSnap.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.yalantis.ucrop.UCrop
import java.io.File

/**
 * Legacy compatibility wrapper.
 *
 * Current correction flow launches uCrop-n-Edit directly from
 * PipelinePreviewActivity. This class is retained only for older callers.
 */
class UcropCorrectionActivity : ComponentActivity() {

    private lateinit var outputFile: File

    private val cropLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val resultUri = result.data?.let { UCrop.getOutput(it) }
                val path = resultUri?.path ?: outputFile.absolutePath
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(EXTRA_RESULT_PATH, path),
                )
            } else {
                setResult(result.resultCode, result.data)
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sourcePath = intent.getStringExtra(EXTRA_SOURCE_PATH)
        if (sourcePath.isNullOrBlank()) {
            finish()
            return
        }

        outputFile = File(cacheDir, "ucrop_legacy_" + System.nanoTime() + ".jpg")

        val options = UCrop.Options().apply {
            setFreeStyleCropEnabled(true)
            setCompressionQuality(95)
            setShowCropGrid(true)
            setShowCropFrame(true)
        }

        val cropIntent = UCrop.of(
            Uri.fromFile(File(sourcePath)),
            Uri.fromFile(outputFile),
        )
            .withOptions(options)
            .withMaxResultSize(
                intent.getIntExtra(EXTRA_MAX_WIDTH, 4000),
                intent.getIntExtra(EXTRA_MAX_HEIGHT, 4000),
            )
            .getIntent(this)
        cropLauncher.launch(cropIntent)
    }

    companion object {
        const val EXTRA_SOURCE_PATH = "formsnap.ucrop.source_path"
        const val EXTRA_RESULT_PATH = "formsnap.ucrop.result_path"
        const val EXTRA_MAX_WIDTH = "formsnap.ucrop.max_width"
        const val EXTRA_MAX_HEIGHT = "formsnap.ucrop.max_height"
    }
}

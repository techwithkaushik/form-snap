package org.techwithkaushik.formSnap.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.yalantis.ucrop.UCrop
import java.io.File

class UcropCorrectionActivity : ComponentActivity() {

    private lateinit var outputFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sourcePath = intent.getStringExtra(EXTRA_SOURCE_PATH)
        if (sourcePath.isNullOrBlank()) {
            finish()
            return
        }

        outputFile = File(cacheDir, "ucrop_" + System.nanoTime() + ".jpg")

        val options = UCrop.Options().apply {
            // FormSnap uses uCrop only for the reliable interactive crop UI.
            // Keep gestures useful for correction without adding another editor.
            setFreeStyleCropEnabled(true)
            setCompressionQuality(95)
            setShowCropGrid(true)
            setAllowedGestures(
                UCropActivity.SCALE,
                UCropActivity.SCALE,
                UCropActivity.ROTATE,
            )
        }

        UCrop.of(
            Uri.fromFile(File(sourcePath)),
            Uri.fromFile(outputFile),
        )
            .withOptions(options)
            .withMaxResultSize(
                intent.getIntExtra(EXTRA_MAX_WIDTH, 4000),
                intent.getIntExtra(EXTRA_MAX_HEIGHT, 4000),
            )
            .start(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != UCrop.REQUEST_CROP) return

        if (resultCode == Activity.RESULT_OK) {
            val resultUri = data?.let(UCrop::getOutput) ?: Uri.fromFile(outputFile)
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(EXTRA_RESULT_PATH, resultUri.path),
            )
        } else {
            setResult(resultCode, data)
        }
        finish()
    }

    companion object {
        private const val UCropActivity = "ucrop"
        // Values used by UCrop.Options#setAllowedGestures.
        const val SCALE = 3
        const val ROTATE = 3
        const val EXTRA_SOURCE_PATH = "formsnap.ucrop.source_path"
        const val EXTRA_RESULT_PATH = "formsnap.ucrop.result_path"
        const val EXTRA_MAX_WIDTH = "formsnap.ucrop.max_width"
        const val EXTRA_MAX_HEIGHT = "formsnap.ucrop.max_height"
    }
}

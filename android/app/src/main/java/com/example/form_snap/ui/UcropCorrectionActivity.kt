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
            setFreeStyleCropEnabled(true)
            setCompressionQuality(95)
            setShowCropGrid(true)
            setShowCropFrame(true)
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
            val resultUri = data?.let { UCrop.getOutput(it) }
            val path = resultUri?.path ?: outputFile.absolutePath
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(EXTRA_RESULT_PATH, path),
            )
        } else {
            setResult(resultCode, data)
        }
        finish()
    }

    companion object {
        const val EXTRA_SOURCE_PATH = "formsnap.ucrop.source_path"
        const val EXTRA_RESULT_PATH = "formsnap.ucrop.result_path"
        const val EXTRA_MAX_WIDTH = "formsnap.ucrop.max_width"
        const val EXTRA_MAX_HEIGHT = "formsnap.ucrop.max_height"
    }
}

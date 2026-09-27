package org.techwithkaushik.formSnap.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.yalantis.ucrop.UCrop
import java.io.File
import kotlin.system.measureNanoTime

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
        val sourceUri = Uri.fromFile(File(sourcePath))
        val destinationUri = Uri.fromFile(outputFile)

        val options = UCrop.Options().apply {
            setFreeStyleCropEnabled(true)
            setCompressionQuality(95)
            setShowCropGrid(true)
        }

        UCrop.of(sourceUri, destinationUri)
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
            val resultUri = data?.let { UCrop.getOutput(it) } ?: Uri.fromFile(outputFile)
            setContentView(createAdjustmentView(resultUri))
        } else {
            setResult(resultCode, data)
            finish()
        }
    }

    private fun createAdjustmentView(uri: Uri): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        root.addView(TextView(this).apply {
            text = "Adjust"
            textSize = 20f
        })

        addSlider(root, "Brightness", -50, 50, 0)
        addSlider(root, "Contrast", -50, 50, 0)
        addSlider(root, "Saturation", -50, 50, 0)
        addSlider(root, "Sharpness", 0, 100, 0)

        root.addView(TextView(this).apply {
            text = "Done"
            textSize = 18f
            setPadding(0, 24, 0, 24)
            setOnClickListener {
                val output = uri.path?.let(::File) ?: outputFile
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(EXTRA_RESULT_PATH, output.absolutePath),
                )
                finish()
            }
        })

        return root
    }

    private fun addSlider(
        root: LinearLayout,
        label: String,
        min: Int,
        max: Int,
        initial: Int,
    ) {
        root.addView(TextView(this).apply { text = label })
        root.addView(SeekBar(this).apply {
            this.max = max - min
            progress = initial - min
        })
    }

    companion object {
        const val EXTRA_SOURCE_PATH = "formsnap.ucrop.source_path"
        const val EXTRA_RESULT_PATH = "formsnap.ucrop.result_path"
        const val EXTRA_MAX_WIDTH = "formsnap.ucrop.max_width"
        const val EXTRA_MAX_HEIGHT = "formsnap.ucrop.max_height"
    }
}

package com.example.form_snap

import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.opencv.android.OpenCVLoader
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "formsnap/opencv")
            .setMethodCallHandler { call, result ->
                if (call.method != "process") {
                    result.notImplemented()
                    return@setMethodCallHandler
                }
                val args = call.arguments as? Map<*, *>
                if (args == null) {
                    result.error("BAD_ARGS", "Missing processing arguments", null)
                    return@setMethodCallHandler
                }
                executor.execute {
                    try {
                        if (!OpenCVLoader.initLocal()) {
                            runOnUiThread { result.error("OPENCV_INIT", "OpenCV initialization failed", null) }
                            return@execute
                        }
                        val output = FormSnapOpenCvProcessor.process(applicationContext, args)
                        runOnUiThread { result.success(output) }
                    } catch (t: Throwable) {
                        runOnUiThread {
                            result.error("OPENCV_PROCESS", t.message ?: "Native OpenCV processing failed", null)
                        }
                    }
                }
            }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}

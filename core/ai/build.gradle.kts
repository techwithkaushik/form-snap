import java.net.URL
import java.nio.file.Files

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val bootstrapModelFile = layout.projectDirectory.file(
    "src/main/assets/formsnap_bootstrap_yolov8n_float32.tflite",
)
val bootstrapModelUrl =
    "https://raw.githubusercontent.com/naz23/yolo-tensorflow-lite/main/yolov8n_float32.tflite"

val downloadBootstrapModel by tasks.registering {
    outputs.file(bootstrapModelFile)
    doLast {
        val target = bootstrapModelFile.asFile
        if (!target.exists() || target.length() < 1_000_000L) {
            target.parentFile.mkdirs()
            URL(bootstrapModelUrl).openStream().use { input ->
                Files.copy(
                    input,
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
        check(target.length() > 1_000_000L) {
            "Bootstrap YOLOv8 model download is missing or incomplete: " + target.absolutePath
        }
    }
}

android {
    namespace = "org.techwithkaushik.formsnap.core.ai"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    androidResources {
        noCompress += "tflite"
    }

    tasks.named("preBuild") {
        dependsOn(downloadBootstrapModel)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.tensorflow.lite)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core)
}

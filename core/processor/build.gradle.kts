plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android()
    sourceSets {
        val androidMain by getting
    }
}

android {
    namespace = "org.techwithkaushik.formsnap.core.processor"
    compileSdk = 37
    minSdk = 23

    buildFeatures {
        buildConfig = false
    }

    ndk {
        abiFilters.addAll(setOf("arm64-v8a", "armeabi-v7a"))
    }

    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        externalNativeBuild {
            cmake {
                val sdk = providers.gradleProperty("opencv.sdk.dir")
                    .orElse(providers.environmentVariable("OPENCV_ANDROID_SDK"))
                    .getOrElse("")
                require(sdk.isNotBlank()) {
                    "OpenCV Android SDK path is required. Set -Popencv.sdk.dir or OPENCV_ANDROID_SDK."
                }
                arguments(
                    "-DOPENCV_ANDROID_SDK=$sdk",
                    "-DOpenCV_DIR=$sdk/sdk/native/jni",
                    "-DANDROID_STL=c++_shared",
                )
            }
        }
    }
}

dependencies {
    implementation("org.opencv:opencv:4.13.0")
}

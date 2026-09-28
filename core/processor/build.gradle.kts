plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android()
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
                    .orElse(
                        providers.fileContents(
                            rootProject.layout.projectDirectory.file("local.properties"),
                        ).asText.map { textValue ->
                            textValue.lineSequence()
                                .map(String::trim)
                                .firstOrNull { it.startsWith("opencv.sdk.dir=") }
                                ?.substringAfter('=')
                                ?.trim()
                                .orEmpty()
                        },
                    )
                    .getOrElse("")
                require(sdk.isNotBlank()) {
                    "OpenCV Android SDK path is required. Set -Popencv.sdk.dir=/path/to/OpenCV-android-sdk or OPENCV_ANDROID_SDK."
                }
                arguments(
                    "-DOPENCV_ANDROID_SDK=$sdk",
                    "-DOpenCV_DIR=$sdk/sdk/native/jni",
                    "-DANDROID_STL=c++_shared",
                )
            }
        }
    }

    packaging {
        jniLibs {
            pickFirsts += setOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
            )
        }
    }
}

dependencies {
    add("androidMainImplementation", "org.opencv:opencv:4.13.0")
}

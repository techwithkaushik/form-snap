plugins {
    id("com.android.library")
}

val opencvSdkDir = providers.gradleProperty("opencv.sdk.dir")
    .orElse(providers.environmentVariable("OPENCV_ANDROID_SDK"))
    .orElse(
        providers.fileContents(
            project.layout.projectDirectory.file("local.properties"),
        ).asText.map { textValue ->
            textValue.lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("opencv.sdk.dir=") }
                ?.substringAfter('=')
                ?.trim()
                .orEmpty()
        },
    )
    .orNull

require(!opencvSdkDir.isNullOrBlank()) {
    "OpenCV Android SDK path is required. Set -Popencv.sdk.dir=/path/to/OpenCV-android-sdk or OPENCV_ANDROID_SDK."
}

android {
    namespace = "org.techwithkaushik.formsnap.core.processor"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
        ndk {
            abiFilters.addAll(setOf("arm64-v8a", "armeabi-v7a"))
            stl = "c++_shared"
        }
        externalNativeBuild {
            cmake {
                arguments(
                    "-DOPENCV_ANDROID_SDK=$opencvSdkDir",
                    "-DOpenCV_DIR=$opencvSdkDir/sdk/native/jni",
                    "-DCMAKE_TOOLCHAIN_FILE=${android.ndkDirectory.absolutePath}/build/cmake/android.toolchain.cmake",
                    "-DANDROID_STL=c++_shared",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    implementation("org.opencv:opencv:4.13.0")
}

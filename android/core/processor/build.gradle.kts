plugins {
    id("com.android.library")
}

val opencvSdkDir = providers.gradleProperty("opencv.sdk.dir")
    .orElse(providers.environmentVariable("OPENCV_ANDROID_SDK"))
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
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            stl = "c++_shared"
        }

        externalNativeBuild {
            cmake {
                arguments(
                    "-DCMAKE_TOOLCHAIN_FILE=$opencvSdkDir/sdk/native/jni/abiFilter/abiFilters.cmake",
                    "-DOPENCV_ANDROID_SDK=$opencvSdkDir",
                    "-DANDROID_STL=c++_shared",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
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

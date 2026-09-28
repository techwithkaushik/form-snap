plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.techwithkaushik.formsnap.core.processor"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
        ndk {
            abiFilters += "armeabi-v7a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti", "-O3")
            }
        }
    }

    buildFeatures {
        prefab = true
        buildConfig = false
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            excludes += "lib/arm64-v8a/**"
            excludes += "lib/x86/**"
            excludes += "lib/x86_64/**"
        }
    }
}

dependencies {
    implementation("org.opencv:opencv:4.13.0")
}

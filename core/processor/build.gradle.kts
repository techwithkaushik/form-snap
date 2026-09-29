plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(libs.androidx.lifecycle.viewmodel)
            }
        }

        getByName("androidMain") {
            dependencies {
                implementation(project(":opencv"))
            }
        }
    }
}

android {
    namespace = "org.techwithkaushik.formsnap.processor"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }

    packaging {
        jniLibs {
            excludes += "lib/arm64-v8a/**"
            excludes += "lib/x86/**"
            excludes += "lib/x86_64/**"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

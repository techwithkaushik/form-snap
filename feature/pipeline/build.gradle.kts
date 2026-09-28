plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget()

    sourceSets {
        val commonMain by getting

        val androidMain by getting {
            dependencies {
                implementation(project(":core:database"))
                implementation(project(":core:processor"))
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.androidx.core)
                implementation(libs.androidx.lifecycle.viewmodel)
                implementation(libs.opencv.android)
            }
        }
    }
}

android {
    namespace = "org.techwithkaushik.formsnap.feature.pipeline"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

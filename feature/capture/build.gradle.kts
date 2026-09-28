plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    androidTarget()
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(platform("androidx.compose:compose-bom:2026.09.00"))
                implementation("androidx.compose.runtime:runtime")
                implementation("androidx.compose.foundation:foundation")
                implementation("androidx.compose.material3:material3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.activity:activity-compose:1.13.0")
                implementation("androidx.activity:activity-ktx:1.13.0")
                implementation("androidx.camera:camera-camera2:1.5.0")
                implementation("androidx.camera:camera-lifecycle:1.5.0")
                implementation("androidx.camera:camera-view:1.5.0")
                implementation("androidx.compose.ui:ui")
                implementation("androidx.core:core-ktx:1.17.0")
                implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
            }
        }
    }
}

android {
    namespace = "org.techwithkaushik.formsnap.feature.capture"
    compileSdk = 37
    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures {
        compose = true
        buildConfig = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

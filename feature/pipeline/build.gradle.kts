plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android()
}

android {
    namespace = "org.techwithkaushik.formsnap.feature.pipeline"
    compileSdk = 37
    minSdk = 23

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    add("androidMainImplementation", project(":core:database"))
    add("androidMainImplementation", project(":core:processor"))
    add("androidMainImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    add("androidMainImplementation", "androidx.core:core-ktx:1.17.0")
    add("androidMainImplementation", "androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    add("androidMainImplementation", "org.opencv:opencv:4.13.0")
}

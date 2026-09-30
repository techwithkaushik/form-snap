plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

// Keep transitive AndroidX versions compatible with AGP 8.5.x and compileSdk 35.
// Some third-party artifacts otherwise select newer AndroidX releases that require
// AGP 8.9.1+ and compileSdk 36, causing :app:checkDebugAarMetadata to fail.
allprojects {
    configurations.configureEach {
        resolutionStrategy.force(
            "androidx.activity:activity:1.9.2",
            "androidx.activity:activity-ktx:1.9.2",
            "androidx.activity:activity-compose:1.9.2",
            "androidx.core:core:1.13.1",
            "androidx.core:core-ktx:1.13.1",
            "androidx.transition:transition:1.5.1",
        )
    }
}

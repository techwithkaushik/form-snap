plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

// Keep CameraX and transitive AndroidX artifacts on a compatible version set.
// Avoid accidental upgrades that exceed the versions validated by this project.
allprojects {
    configurations.configureEach {
        resolutionStrategy.force(
            "androidx.activity:activity:1.13.0",
            "androidx.activity:activity-ktx:1.13.0",
            "androidx.activity:activity-compose:1.13.0",
            "androidx.core:core:1.19.1",
            "androidx.core:core-ktx:1.19.1",
            "androidx.transition:transition:1.7.2",
        )
    }
}

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
            "androidx.activity:activity:1.9.2",
            "androidx.activity:activity-ktx:1.9.2",
            "androidx.activity:activity-compose:1.9.2",
            "androidx.core:core:1.13.1",
            "androidx.core:core-ktx:1.13.1",
            "androidx.transition:transition:1.5.1",
        )
    }
}

plugins {
    id("com.android.library")
}

android {
    namespace = "org.techwithkaushik.formsnap.feature.pipeline"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation(project(":core:database"))
    implementation(project(":core:processor"))
}
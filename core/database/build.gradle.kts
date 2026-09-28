plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        getByName("androidMain") {
            dependencies {
                implementation(libs.sqldelight.android)
                implementation("app.cash.sqldelight:android-driver:2.0.2")
                implementation(libs.kotlinx.coroutines.android)
            }
        }
    }
}

android {
    namespace = "org.techwithkaushik.formsnap.database"
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

tasks.register("compileKotlinAndroid") {
    group = "verification"
    description = "Compatibility lifecycle task for the Android Kotlin compilation."
    dependsOn("compileReleaseKotlinAndroid")
}

sqldelight {
    databases {
        create("LearningDatabase") {
            packageName.set("org.techwithkaushik.formsnap.database")
        }
    }
}

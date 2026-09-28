plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    
    sourceSets {
        commonMain.dependencies {
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }

        sourceSets.all {
            if (name == "androidMain") {
                dependencies {
                    implementation(libs.sqldelight.android.driver)
                    implementation(libs.kotlinx.coroutines.android)
                    
                    compileOnly("com.google.android:android:4.1.1.4")
                }
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

sqldelight {
    databases {
        create("LearningDatabase") {
            packageName.set("org.techwithkaushik.formsnap.database")
        }
    }
}

tasks.matching { it.name.contains("compileReleaseKotlinAndroid") || it.name.contains("compileDebugKotlinAndroid") }.all {
    dependsOn(tasks.matching { it.name.contains("processReleaseManifest") || it.name.contains("processDebugManifest") })
}

tasks.register("compileKotlinAndroid") {
    group = "verification"
    description = "Compatibility lifecycle task for the Android Kotlin compilation."
    dependsOn("compileReleaseKotlinAndroid")
}

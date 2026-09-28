plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
    id("app.cash.sqldelight")
}

kotlin {
    androidTarget()
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("app.cash.sqldelight:runtime:2.3.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("app.cash.sqldelight:android-driver:2.3.2")
            }
        }
    }
}

android {
    namespace = "org.techwithkaushik.formsnap.core.database"
    compileSdk = 37
    defaultConfig { minSdk = 23 }
}

sqldelight {
    databases {
        create("FormSnapDatabase") {
            packageName.set("org.techwithkaushik.formsnap.core.database")
        }
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.example.form_snap"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.example.form_snap"
        minSdk = 23
        targetSdk = 37
        versionCode = 3
        versionName = "0.2.0"
        ndk { abiFilters += "armeabi-v7a" }
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        jniLibs {
            excludes += "lib/arm64-v8a/**"
            excludes += "lib/x86/**"
            excludes += "lib/x86_64/**"
        }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val store = System.getenv("SIGNING_KEY_STORE")
            val storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            val alias = System.getenv("SIGNING_KEY_ALIAS")
            val keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            if (!store.isNullOrBlank() && !storePassword.isNullOrBlank() &&
                !alias.isNullOrBlank() && !keyPassword.isNullOrBlank()
            ) {
                signingConfig = signingConfigs.create("releaseSigning") {
                    storeFile = file(store)
                    this.storePassword = storePassword
                    keyAlias = alias
                    this.keyPassword = keyPassword
                }
            }
        }
    }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.opencv:opencv:4.13.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

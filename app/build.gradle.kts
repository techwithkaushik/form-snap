plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.techwithkaushik.formsnap"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.techwithkaushik.formsnap"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        // CI injects branch-aware versionName and commit-count-based versionCode.
        // Local builds keep a stable development fallback.
        versionCode = System.getenv("APP_VERSION_CODE")?.toIntOrNull()?.coerceAtLeast(1) ?: 3
        versionName = System.getenv("APP_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.2.0-local"

        // FormSnap's supported delivery target is 32-bit ARM (armeabi-v7a).
        // Avoid packaging native binaries for devices this APK is not intended for.
        ndk {
            abiFilters += "armeabi-v7a"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs {
            // Compress OpenCV/TFLite native libraries in the downloadable APK.
            // Android extracts these libraries at install time on supported devices.
            useLegacyPackaging = true
            excludes += "lib/arm64-v8a/**"
            excludes += "lib/x86/**"
            excludes += "lib/x86_64/**"
            excludes += "lib/armeabi/**"
            excludes += "lib/mips/**"
            excludes += "lib/mips64/**"
            excludes += "lib/riscv64/**"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                file("proguard-rules.pro"),
            )

            val store = System.getenv("SIGN_KEY_STORE")
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
    testImplementation(libs.junit)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(project(":core:ai"))
    implementation(project(":feature:capture"))
    implementation(project(":opencv"))
}

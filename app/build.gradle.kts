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
        versionCode = 3
        versionName = "0.2.0"

        ndk {
            abiFilters += setOf(
                "arm64-v8a",
                "armeabi-v7a",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs {
            pickFirsts += setOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
            )
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

            if (
                !store.isNullOrBlank() &&
                !storePassword.isNullOrBlank() &&
                !alias.isNullOrBlank() &&
                !keyPassword.isNullOrBlank()
            ) {
                signingConfig = signingConfigs.create(
                    "releaseSigning",
                ) {
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
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(project(":core:processor"))
    implementation(project(":core:database"))
    implementation(project(":feature:capture"))
    implementation(project(":feature:pipeline"))
    debugImplementation(libs.androidx.lifecycle.runtime)
}

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
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs {
            excludes += "lib/arm64-v8a/**"
            excludes += "lib/x86/**"
            excludes += "lib/x86_64/**"
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
    implementation("com.github.jens-muenker:uCrop-n-Edit:4.1.1-non-native")
    implementation(libs.androidx.core)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(project(":core:processor"))
    implementation(project(":core:database"))
    implementation(project(":feature:capture"))
    implementation(project(":feature:pipeline"))
    implementation(project(":opencv"))
    debugImplementation(libs.androidx.lifecycle.runtime)
}

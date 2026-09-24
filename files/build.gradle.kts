plugins {
    id("com.android.application")
    id("kotlin-android")
    id("dev.flutter.flutter-gradle-plugin")
}
android {
    namespace = "com.example.form_snap"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = JavaVersion.VERSION_17.toString() }
    defaultConfig {
        applicationId = "com.example.form_snap"
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        // FormSnap's CI produces the ARM32 APK. OpenCV otherwise packages
        // native libraries for several ABIs, which makes the APK unnecessarily
        // large. Keep only the ABI that the requested APK actually contains.
        ndk {
            abiFilters.clear()
            abiFilters.add("armeabi-v7a")
        }
    }
    // The Flutter ARM32 build still allows the OpenCV AAR to contribute
    // other native ABIs. Explicitly exclude them so the APK contains only
    // armeabi-v7a. This removes the 24 MB arm64 and 59 MB x86_64 OpenCV
    // binaries from the release APK.
    packaging {
        jniLibs {
            excludes += "lib/arm64-v8a/**"
            excludes += "lib/x86/**"
            excludes += "lib/x86_64/**"
        }
    }
    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("SIGNING_KEY_STORE")
            val storePasswordEnv = System.getenv("SIGNING_STORE_PASSWORD")
            val keyAliasEnv = System.getenv("SIGNING_KEY_ALIAS")
            val keyPasswordEnv = System.getenv("SIGNING_KEY_PASSWORD")
            if (!storeFilePath.isNullOrBlank() && !storePasswordEnv.isNullOrBlank() && !keyAliasEnv.isNullOrBlank() && !keyPasswordEnv.isNullOrBlank()) {
                storeFile = file(storeFilePath)
                storePassword = storePasswordEnv
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
            } else println("⚠️ Release signing is NOT configured (missing env vars).")
        }
    }
    sourceSets {
        getByName("main") {
            manifest.srcFile("../../files/AndroidManifest.xml")
            java.srcDirs("../../files/android")
            kotlin.srcDirs("../../files/android")
        }
    }
    buildTypes {
        getByName("release") {
            val hasSigning = !System.getenv("SIGNING_KEY_STORE").isNullOrBlank()
            signingConfig = if (hasSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
        }
        getByName("debug") {}
    }
}
dependencies {
    implementation("org.opencv:opencv:4.13.0")
}
flutter { source = "../.." }

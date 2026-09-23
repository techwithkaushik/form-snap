plugins { 

    id("com.android.application") 

    id("kotlin-android") 

    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins. 

    id("dev.flutter.flutter-gradle-plugin") 

} 

android { 

    namespace = "com.techwithkaushik.form_snap" 

    compileSdk = flutter.compileSdkVersion 

    ndkVersion = flutter.ndkVersion 

    compileOptions { 

        sourceCompatibility = JavaVersion.VERSION_17 

        targetCompatibility = JavaVersion.VERSION_17 

    } 

    kotlinOptions { 

        jvmTarget = JavaVersion.VERSION_17.toString() 

    } 

    defaultConfig { 

        // TODO: Specify your own unique Application ID ([https://developer.android.com/studio/build/application-id.html](https://developer.android.com/studio/build/application-id.html)). 

        applicationId = "com.techwithkaushik.form_snap"

        // You can update the following values to match your application needs. 

        // For more information, see: [https://flutter.dev/to/review-gradle-config.](https://flutter.dev/to/review-gradle-config) 

        minSdk = flutter.minSdkVersion 

        targetSdk = flutter.targetSdkVersion 

        versionCode = flutter.versionCode 

        versionName = flutter.versionName 

    }

    signingConfigs {
        create("release") {
            // Read secrets from environment (set in GitHub Actions)
            val storeFilePath   = System.getenv("SIGNING_KEY_STORE")
            val storePasswordEnv = System.getenv("SIGNING_STORE_PASSWORD")
            val keyAliasEnv      = System.getenv("SIGNING_KEY_ALIAS")
            val keyPasswordEnv   = System.getenv("SIGNING_KEY_PASSWORD")

            if (
                !storeFilePath.isNullOrBlank() &&
                !storePasswordEnv.isNullOrBlank() &&
                !keyAliasEnv.isNullOrBlank() &&
                !keyPasswordEnv.isNullOrBlank()
            ) {
                storeFile = file(storeFilePath)
                storePassword = storePasswordEnv
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
            } else {
                // Optional: log a warning for local builds without signing
                println("⚠️ Release signing is NOT configured (missing env vars).")
            }
        }
    }
        
    sourceSets {
        getByName("main") {
            manifest.srcFile("../../files/AndroidManifest.xml")
        }
    }

    buildTypes { 
        getByName("release") {
            // Only use release signing if env vars exist
            val hasSigning = !System.getenv("SIGNING_KEY_STORE").isNullOrBlank()
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // Fallback: use debug signing so `./gradlew assembleRelease` still works
                signingConfig = signingConfigs.getByName("debug")
            }
            // your other release options...
            isMinifyEnabled = true
            isShrinkResources = true
        }

        getByName("debug") {
            // Keep default debug signing (no change needed)
        }
    } 

} 

flutter { 

    source = "../.." 

}

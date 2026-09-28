plugins {
    id("com.android.library")
    id("app.cash.sqldelight")
    id("org.jetbrains.kotlin.android")
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

dependencies {
    implementation("app.cash.sqldelight:android-driver:2.3.2")
    implementation("app.cash.sqldelight:runtime:2.3.2")
}

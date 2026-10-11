pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(org.gradle.api.initialization.resolve.RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "FormSnap"

include(":core:ai", ":feature:capture", ":opencv", ":app", ":dataset-builder")

project(":core:ai").projectDir = file("core/ai")
project(":feature:capture").projectDir = file("feature/capture")
project(":opencv").projectDir = file("opencv")
project(":app").projectDir = file("app")
project(":dataset-builder").projectDir = file("dataset-builder")

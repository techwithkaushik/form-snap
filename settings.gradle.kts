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

include(":core:database", ":core:processor", ":core:ai", ":feature:capture", ":feature:pipeline", ":opencv", ":app")

project(":core:database").projectDir = file("core/database")
project(":core:processor").projectDir = file("core/processor")
project(":core:ai").projectDir = file("core/ai")
project(":feature:capture").projectDir = file("feature/capture")
project(":feature:pipeline").projectDir = file("feature/pipeline")
project(":opencv").projectDir = file("opencv")
project(":app").projectDir = file("app")

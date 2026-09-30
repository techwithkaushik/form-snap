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

include(":core:database", ":core:processor", ":feature:capture", ":feature:pipeline", ":opencv", ":app")

project(":core:database").projectDir = file("core/database")
project(":core:processor").projectDir = file("core/processor")
project(":feature:capture").projectDir = file("feature/capture")
project(":feature:pipeline").projectDir = file("feature/pipeline")
// The CI workflow copies the official Android SDK into opencv/, where the Gradle library module lives in java/.
project(":opencv").projectDir = file("opencv/java")
project(":app").projectDir = file("app")

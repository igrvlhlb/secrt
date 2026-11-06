pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "secrt"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
    ":core:model",
    ":core:crypto",
    ":core:vault",
    ":core:sync",
    ":core:storage",
    ":core:archive",
    ":core:session",
    ":ui",
    ":app:android",
    ":app:desktop",
)

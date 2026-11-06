pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Downloads JDK toolchains a build asks for when they aren't installed (e.g. the desktop runtime).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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
    ":core:backup",
    ":core:session",
    ":core:lan",
    ":ui",
    ":app:android",
    ":app:desktop",
)

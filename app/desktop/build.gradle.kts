import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(projects.ui)
    // Skiko's native renderer for the OS doing the build, so a package targets that OS.
    implementation(compose.desktop.currentOs)
    // Dispatchers.Main on the AWT event thread (ViewModels and Compose effects need it).
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// So `./gradlew jvmTest` also runs this module's tests, like the multiplatform modules' JVM tests.
tasks.register("jvmTest") { dependsOn(tasks.test) }

compose.desktop {
    application {
        mainClass = "io.github.igrvlhlb.secrt.desktop.MainKt"
        // Run and package with the toolchain JDK, not whichever JDK runs Gradle.
        javaHome = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }
            .get().metadata.installationPath.asFile.absolutePath
        // `./gradlew :app:desktop:run -Psecrt.dataDir=/some/dir` runs against a throwaway diary.
        providers.gradleProperty("secrt.dataDir").orNull?.let { jvmArgs("-Dsecrt.dataDir=$it") }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "secrt"
            packageVersion = "0.1.0"
            description = "Encrypted, local-first diary"
            // The bundled runtime only has the JDK modules listed here (from `suggestRuntimeModules`):
            // JDBC for the database, and jdk.unsupported for JNA (libsodium).
            modules("java.instrument", "java.sql", "jdk.unsupported")

            macOS {
                bundleID = "io.github.igrvlhlb.secrt"
                // jpackage refuses a 0.x version on macOS.
                packageVersion = "1.0.0"
                dmgPackageVersion = "1.0.0"
                // Not signed or notarized yet (see BACKLOG.md): Gatekeeper will block the app.
            }
        }
    }
}

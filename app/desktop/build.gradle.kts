import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(17)
}

/**
 * JDK the desktop app runs on and bundles. Our code still compiles for Java 17, but some
 * dependencies don't (the Markdown renderer ships Java 21 bytecode), so the runtime must be newer.
 * The packaged app carries its own runtime, so users never need a JDK installed.
 */
val desktopRuntime = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }

// JNA (libsodium) and the SQLite driver load native code; JDK 24+ warns unless this is allowed.
val desktopJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")

dependencies {
    implementation(projects.ui)
    // Skiko's native renderer for the OS doing the build, so a package targets that OS.
    implementation(compose.desktop.currentOs)
    // Dispatchers.Main on the AWT event thread (ViewModels and Compose effects need it).
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// Tests run on the same JDK as the app, so they catch dependencies it can't load.
tasks.test {
    javaLauncher = desktopRuntime
    jvmArgs(desktopJvmArgs)
}

/**
 * The Google OAuth client ("Desktop app" type) for Google Drive backup, from `local.properties`
 * (or `-P`): `secrt.google.desktopClientId` and `secrt.google.desktopClientSecret`. Its secret
 * isn't really one (it ships in every copy of the app), but it stays out of the repository. Without
 * them the app is built without Google Drive.
 */
val localProperties = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText
    .map { text -> Properties().apply { load(text.reader()) } }

fun googleSetting(name: String): Provider<String> =
    providers.gradleProperty(name).orElse(localProperties.map { it.getProperty(name).orEmpty() }).orElse("")

val generateGoogleConfig = tasks.register("generateGoogleConfig") {
    val clientId = googleSetting("secrt.google.desktopClientId")
    val clientSecret = googleSetting("secrt.google.desktopClientSecret")
    val output = layout.buildDirectory.dir("generated/googleConfig")
    inputs.property("clientId", clientId)
    inputs.property("clientSecret", clientSecret)
    outputs.dir(output)
    doLast {
        output.get().file("secrt-google.properties").asFile.apply {
            parentFile.mkdirs()
            writeText("desktopClientId=${clientId.get()}\ndesktopClientSecret=${clientSecret.get()}\n")
        }
    }
}

sourceSets.main {
    resources.srcDir(generateGoogleConfig)
}

// So `./gradlew jvmTest` also runs this module's tests, like the multiplatform modules' JVM tests.
tasks.register("jvmTest") { dependsOn(tasks.test) }

compose.desktop {
    application {
        mainClass = "io.github.igrvlhlb.secrt.desktop.MainKt"
        // Run and package with the desktop runtime JDK, not whichever JDK runs Gradle.
        javaHome = desktopRuntime.get().metadata.installationPath.asFile.absolutePath
        jvmArgs(*desktopJvmArgs.toTypedArray())
        // `./gradlew :app:desktop:run -Psecrt.dataDir=/some/dir` runs against a throwaway diary.
        providers.gradleProperty("secrt.dataDir").orNull?.let { jvmArgs("-Dsecrt.dataDir=$it") }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "secrt"
            packageVersion = "0.1.0"
            description = "Encrypted, local-first diary"
            // The bundled runtime only has the JDK modules listed here (from `suggestRuntimeModules`):
            // JDBC for the database, jdk.unsupported for JNA (libsodium). java.management is listed
            // since the HTTP client for cloud backup joined; a spare module is cheap, a missing one
            // only fails at runtime. TLS needs nothing extra (the EC crypto lives in java.base since JDK 22).
            modules("java.instrument", "java.management", "java.sql", "jdk.unsupported")

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

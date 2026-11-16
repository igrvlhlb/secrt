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
 *
 * Temurin 21, the same on every machine (downloaded by the foojay resolver when missing). Not 24+:
 * from 24 on, Temurin ships without `jmods/` (JEP 493), which the release build's ProGuard step
 * reads the JDK's own classes from. See docs/release.md.
 */
val desktopRuntime = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(21)
    vendor = JvmVendorSpec.ADOPTIUM
}

// JNA (libsodium) and the SQLite driver load native code; newer JDKs warn unless this is allowed.
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

        // `packageRelease*` shrinks the jars with ProGuard first. Libraries that use reflection, JNI
        // or JNA need keep rules there; a missing one only fails when the packaged app runs.
        // Shrinking only: ProGuard's optimizer rewrote Okio's return types into bytecode the JVM
        // rejects (VerifyError: Bad return type), and it saves little next to shrinking.
        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
            optimize.set(false)
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "secrt"
            packageVersion = "0.1.0"
            description = "Encrypted, local-first diary"
            // The bundled runtime only has the JDK modules listed here (from `suggestRuntimeModules`):
            // JDBC for the database, jdk.unsupported for JNA (libsodium). java.management is listed
            // since the HTTP client for cloud backup joined; a spare module is cheap, a missing one
            // only fails at runtime. jdk.crypto.ec: TLS key exchange on JDK 21 (it moved into java.base in 22).
            modules("java.instrument", "java.management", "java.sql", "jdk.crypto.ec", "jdk.unsupported")

            macOS {
                bundleID = "io.github.igrvlhlb.secrt"
                // jpackage refuses a 0.x version on macOS.
                packageVersion = "1.0.0"
                dmgPackageVersion = "1.0.0"
                iconFile.set(project.file("packaging/secrt.icns"))
                // Not signed or notarized yet (see BACKLOG.md): Gatekeeper will block the app.
            }
            windows {
                iconFile.set(project.file("packaging/secrt.ico"))
            }
            linux {
                iconFile.set(project.file("packaging/secrt.png"))
            }
        }
    }
}

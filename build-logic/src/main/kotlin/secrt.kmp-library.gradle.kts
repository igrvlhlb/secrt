// Convention for every shared (Kotlin Multiplatform) module.
// Targets: Android (the app we ship first) and JVM (fast host tests, and the future desktop app).
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun version(alias: String) = libs.findVersion(alias).get().requiredVersion

kotlin {
    jvmToolchain(17)
    jvm()
    android {
        namespace = "io.github.igrvlhlb.secrt" + project.path.replace(':', '.').replace('-', '_')
        compileSdk = version("android-compileSdk").toInt()
        minSdk = version("android-minSdk").toInt()
    }

    compilerOptions {
        optIn.addAll(
            "kotlin.uuid.ExperimentalUuidApi",
            "kotlin.time.ExperimentalTime",
            "kotlin.ExperimentalUnsignedTypes",
        )
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

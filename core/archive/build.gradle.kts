plugins {
    id("secrt.kmp-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.vault)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio.fakefilesystem)
        }
    }
}

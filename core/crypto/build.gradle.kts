plugins {
    id("secrt.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.libsodium.bindings)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

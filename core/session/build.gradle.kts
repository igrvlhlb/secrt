plugins {
    id("secrt.kmp-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.storage)
            api(projects.core.archive)
        }
        androidMain.dependencies {
            // BiometricPrompt. `api` because AndroidBiometricKeyStore.attach takes a FragmentActivity.
            api(libs.androidx.biometric)
            api(libs.androidx.fragment)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio.fakefilesystem)
        }
    }
}

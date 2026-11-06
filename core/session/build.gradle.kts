plugins {
    id("secrt.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.storage)
            api(projects.core.archive)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio.fakefilesystem)
        }
    }
}

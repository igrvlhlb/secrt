plugins {
    id("secrt.kmp-library")
    id("app.cash.sqldelight")
}

sqldelight {
    databases {
        create("SecrtDatabase") {
            packageName.set("io.github.igrvlhlb.secrt.storage.db")
        }
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.sync)
            api(projects.core.vault)
            api(libs.sqldelight.runtime)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.sqldelight.coroutines)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.sqlcipher.android)
            implementation(libs.androidx.sqlite)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio.fakefilesystem)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.jvm.driver)
        }
    }
}

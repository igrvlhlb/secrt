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
        jvmMain.dependencies {
            // SQLDelight's JDBC driver, running on sqlite-jdbc-crypt instead of xerial's sqlite-jdbc.
            // Both ship the `org.sqlite` classes, so the plain one must never reach the classpath.
            api(libs.sqldelight.jvm.driver.get().toString()) {
                exclude(group = "org.xerial", module = "sqlite-jdbc")
            }
            implementation(libs.sqlite.jdbc.crypt)
        }
    }
}

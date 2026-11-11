plugins {
    id("secrt.kmp-library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    // Compose resources: the app's texts and their translations (docs/architecture.md, "Languages").
    id("org.jetbrains.compose")
}

compose.resources {
    packageOfResClass = "io.github.igrvlhlb.secrt.ui.resources"
    publicResClass = false
    generateResClass = always
}

kotlin {
    android {
        // Packs the Compose resources (texts and translations) into the app.
        androidResources.enable = true
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.core.session)
            api(projects.core.backup)
            api(projects.core.lan)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.markdown.renderer.m3)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            // Scanning the computer's pairing QR code.
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.zxing.core)
        }
        jvmMain.dependencies {
            // Drawing the pairing QR code.
            implementation(libs.zxing.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // Skiko's native part: resolving texts outside composition asks it for the system theme.
            implementation(compose.desktop.currentOs)
        }
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "kotlinx.coroutines.FlowPreview",
        )
    }
}

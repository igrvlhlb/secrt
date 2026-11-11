plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.igrvlhlb.secrt.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.igrvlhlb.secrt"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing, from Gradle properties kept outside the repo (~/.gradle/gradle.properties, or
    // -P on the command line); see docs/release.md. Without them the release
    // build is unsigned, which is what F-Droid wants: it builds from source and signs it itself.
    val signing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .associateWith { providers.gradleProperty("secrt.signing.$it").orNull?.takeIf(String::isNotBlank) }
    val releaseSigning = if (signing.values.all { it != null }) {
        signingConfigs.create("release") {
            storeFile = file(signing.getValue("storeFile")!!)
            storePassword = signing.getValue("storePassword")
            keyAlias = signing.getValue("keyAlias")
            keyPassword = signing.getValue("keyPassword")
        }
    } else {
        check(signing.values.all { it == null }) {
            "Release signing needs all of secrt.signing.{storeFile,storePassword,keyAlias,keyPassword}; " +
                "missing: " + signing.filterValues { it == null }.keys.joinToString()
        }
        null
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(projects.ui)
    implementation(libs.androidx.activity.compose)
}

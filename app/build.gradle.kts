import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.schaubeck.waddeln"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.schaubeck.waddeln"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "1.5.1"
    }

    // The release key is read from keystore.properties (not checked in); without it the
    // release build stays unsigned.
    val keystoreFile = rootProject.file("keystore.properties")
    val releaseSigning = if (keystoreFile.exists()) {
        val props = Properties().apply { keystoreFile.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    } else {
        null
    }

    buildTypes {
        release {
            signingConfig = releaseSigning
            // R8 shrinks the code; keep rules are in src/main/keepRules.
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    // The dependency list is encrypted with a Google key; F-Droid rejects APKs that contain it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":rules-core"))
    implementation(project(":protocol"))
    implementation(project(":shared"))
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    // Screens, view models and Material 3 come with :shared (Compose Multiplatform).
    debugImplementation(libs.androidx.compose.ui.tooling)
}

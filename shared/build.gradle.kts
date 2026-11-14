plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// UI, view models and game flow shared by the Android app and the web app. Platform services
// (storage, sound, network, model) are interfaces here; the Android shell implements them.
kotlin {
    androidLibrary {
        namespace = "com.schaubeck.waddeln.shared"
        compileSdk = 37
        minSdk = 26
        androidResources {
            enable = true
        }
        withHostTest {}
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":rules-core"))
            api(project(":protocol"))
            api(libs.compose.mp.runtime)
            api(libs.compose.mp.foundation)
            api(libs.compose.mp.ui)
            api(libs.compose.mp.material3)
            api(libs.compose.mp.resources)
            api(libs.compose.mp.lifecycle.viewmodel)
            api(libs.compose.mp.lifecycle.runtime)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
        }
    }
}

// Keeps `./gradlew test` covering this module: Android host tests and Wasm tests in the browser.
tasks.register("test") {
    group = "verification"
    description = "Runs the tests of all targets."
    dependsOn("allTests")
}

compose.resources {
    packageOfResClass = "com.schaubeck.waddeln.resources"
    publicResClass = true
}

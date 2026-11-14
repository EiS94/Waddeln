plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

// The browser version: the shared app with web implementations of the platform services.
// Development: ./gradlew :webApp:wasmJsBrowserDevelopmentRun   Package: :webApp:wasmJsBrowserDistribution
kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        compilerOptions {
            optIn.add("kotlin.js.ExperimentalWasmJsInterop")
        }
        outputModuleName.set("webApp")
        browser {
            commonWebpackConfig {
                outputFileName = "webApp.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.mp.resources)
        }
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.kotlinx.browser)
        }
    }
}

compose.resources {
    packageOfResClass = "com.schaubeck.waddeln.web.resources"
}

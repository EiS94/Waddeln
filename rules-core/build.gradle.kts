plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            // Only the annotations/serializers, so Observation and Action can be sent over the network.
            api(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
            // ONNX Runtime (JVM) only to verify the model interface; the app uses onnxruntime-android.
            implementation(libs.onnxruntime)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// Keeps the familiar `:<module>:test` (and `./gradlew test`): all targets, JVM and browser.
tasks.register("test") {
    group = "verification"
    description = "Runs the tests of all targets (JVM and Wasm in the browser)."
    dependsOn("allTests")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Writes JSON game protocols for the Python parity tests (training/tests/test_parity.py).
// Arguments: output file, number of matches, number of matches that include feature vectors.
tasks.register<JavaExec>("generateProtocols") {
    group = "verification"
    description = "Generates random game protocols for the Python engine parity check."
    val main = kotlin.jvm().compilations["main"]
    classpath = files(main.output.allOutputs, main.runtimeDependencyFiles)
    mainClass.set("com.schaubeck.waddeln.rules.protocol.ProtocolGenerator")
    args(rootProject.file("training/protocols/protocols.jsonl").absolutePath, "300", "40")
}

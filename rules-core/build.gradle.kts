plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Only the annotations/serializers, so Observation and Action can be sent over the network.
    api(libs.kotlinx.serialization.core)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // ONNX Runtime (JVM) only to verify the model interface; the app uses onnxruntime-android.
    testImplementation(libs.onnxruntime)
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.test {
    useJUnitPlatform()
}

// Writes JSON game protocols for the Python parity tests (training/tests/test_parity.py).
// Arguments: output file, number of matches, number of matches that include feature vectors.
tasks.register<JavaExec>("generateProtocols") {
    group = "verification"
    description = "Generates random game protocols for the Python engine parity check."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.schaubeck.waddeln.rules.protocol.ProtocolGenerator")
    args(rootProject.file("training/protocols/protocols.jsonl").absolutePath, "300", "40")
}

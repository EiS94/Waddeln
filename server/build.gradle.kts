plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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

application {
    mainClass.set("com.schaubeck.waddeln.server.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx256m")
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.onnxruntime)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.core)
}

// The server bots use the same network as the app.
tasks.processResources {
    from(rootProject.file("app/src/main/assets/models")) { into("models") }
}

tasks.test {
    useJUnitPlatform()
}

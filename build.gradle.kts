plugins {
    kotlin("jvm") version "2.4.21"
}

group = "ai.yuriilab.recursivecoder"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

dependencies {
    // A pinned Koog API on the compilation classpath. Runtime integration
    // and observable sessions are implemented separately in #785.
    implementation("ai.koog:koog-agents:1.3.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test {
    useJUnitPlatform()
}

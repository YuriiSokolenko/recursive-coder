plugins {
    kotlin("jvm") version "2.4.21"
    application
}

group = "ai.yuriilab.recursivecoder"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("ai.yuriilab.recursivecoder.MainKt")
}

dependencies {
    implementation("ai.koog:koog-agents:1.3.0")
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.installDist)
}

plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

kotlin { jvmToolchain(21) }

val kotlinLibraries = extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(kotlinLibraries.findLibrary("kotlin-test-junit5").get())
    "testRuntimeOnly"("org.junit.jupiter:junit-jupiter-engine:5.12.2")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

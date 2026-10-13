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

// The existing 'test' task is deliberately unfiltered and retains every test.
tasks.register<Test>("unitTest") {
    group = "verification"
    description = "Pure in-memory unit tests with no filesystem, Git or HTTP fixtures."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("unit") }
}
tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Local filesystem, Git and mock-HTTP adapter integration tests."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
        excludeTags("e2e")
    }
}
tasks.register<Test>("e2eTest") {
    group = "verification"
    description = "JVM sandbox end-to-end tests; installed CLI E2E runs separately."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("e2e") }
}

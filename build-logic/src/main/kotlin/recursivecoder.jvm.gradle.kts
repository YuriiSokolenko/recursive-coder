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

// Local Gradle vocabulary; independent Python checks deliberately retain their own expectations.
val testSourceSetName = "test"
val unitTag = "unit"
val integrationTag = "integration"
val e2eTag = "e2e"

// The existing 'test' task is deliberately unfiltered and retains every test.
tasks.register<Test>("unitTest") {
    group = "verification"
    description = "Pure in-memory unit tests with no filesystem, Git or HTTP fixtures."
    testClassesDirs = sourceSets[testSourceSetName].output.classesDirs
    classpath = sourceSets[testSourceSetName].runtimeClasspath
    useJUnitPlatform { includeTags(unitTag) }
}
tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Local filesystem, Git and mock-HTTP adapter integration tests."
    testClassesDirs = sourceSets[testSourceSetName].output.classesDirs
    classpath = sourceSets[testSourceSetName].runtimeClasspath
    useJUnitPlatform {
        includeTags(integrationTag)
        excludeTags(e2eTag)
    }
}
tasks.register<Test>("e2eTest") {
    group = "verification"
    description = "JVM sandbox end-to-end tests; installed CLI E2E runs separately."
    testClassesDirs = sourceSets[testSourceSetName].output.classesDirs
    classpath = sourceSets[testSourceSetName].runtimeClasspath
    useJUnitPlatform { includeTags(e2eTag) }
}

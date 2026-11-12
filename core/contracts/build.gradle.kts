plugins {
    id("recursivecoder.jvm")
    `java-test-fixtures`
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    "testFixturesImplementation"(libs.kotlin.test.junit5)
    "testFixturesImplementation"(libs.junit.jupiter.api)
}

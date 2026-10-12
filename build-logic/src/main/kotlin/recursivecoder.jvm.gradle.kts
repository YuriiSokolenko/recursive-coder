plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

kotlin { jvmToolchain(21) }

dependencies {
    "testImplementation"("org.jetbrains.kotlin:kotlin-test-junit5:2.4.21")
    "testRuntimeOnly"("org.junit.jupiter:junit-jupiter-engine:5.12.2")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

plugins { `kotlin-dsl` }

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    // The JUnit BOM aligns Jupiter 5.12.2 with Platform Launcher 1.12.2.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.aggregate)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }

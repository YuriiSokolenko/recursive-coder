plugins { `kotlin-dsl` }

repositories { gradlePluginPortal(); mavenCentral() }

dependencies { implementation(libs.kotlin.gradle.plugin) }

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test { useJUnitPlatform() }

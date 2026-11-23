plugins {
    id("recursivecoder.jvm")
    `java-test-fixtures`
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    "testFixturesImplementation"(libs.kotlin.test.junit5)
    "testFixturesImplementation"(libs.junit.jupiter.api)
}

// Compile negative fixtures separately; never execute them as JUnit tests.
val tierGuardFixture = sourceSets.create("tierGuardFixture")
tierGuardFixture.compileClasspath += sourceSets["test"].compileClasspath
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTierGuardFixtureKotlin") {
    compilerOptions {
        freeCompilerArgs.add("-Xlambdas=class")
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.ENABLE)
    }
}
tasks.register<Exec>("checkKotlinTierFixtures") {
    group = "verification"
    dependsOn(tasks.named(tierGuardFixture.classesTaskName))
    workingDir(rootProject.projectDir)
    commandLine("python3", "tests/check_kotlin_tier_fixtures.py",
        layout.buildDirectory.dir("classes/kotlin/tierGuardFixture").get().asFile.absolutePath)
}

plugins { base; id("recursivecoder.architecture") }

allprojects {
    group = "ai.yuriilab.recursivecoder"
    version = "0.1.0-SNAPSHOT"
}

// Examine compiled JUnit annotations, not class name patterns or source grep.
// Keep legacy 'test' unfiltered: the independent guard makes missing tags fatal.
val testTierGuard = tasks.register<Exec>("checkTestTiers") {
    group = "verification"
    description = "Require JUnit tiers and reject I/O references in pure unit fixtures."
    workingDir(rootDir)
    commandLine("python3", "tools/check_test_tiers.py", "--root", rootDir.absolutePath)
}
// The root check lifecycle runs this single guard after testClasses in every JVM
// subproject; no per-module check dependency is needed. Legacy test is unfiltered.
tasks.named("check") { dependsOn(testTierGuard) }

gradle.projectsEvaluated {
    val javaModules = rootProject.subprojects.filter { it.plugins.hasPlugin("java") }
        .sortedBy { it.path }
    tasks.register("printTestTierModules") {
        group = "verification"
        // Independent project inventory: every configured project with a
        // testClasses task, not the guard's javaModules selection.
        val expected = rootProject.subprojects
            .filter { "testClasses" in it.tasks.names }
            .map { it.path + ":testClasses" }.sorted()
        doLast { expected.forEach { println("TEST_TIER_MODULE=" + it) } }
    }
    testTierGuard.configure {
        javaModules.forEach { module ->
            dependsOn(module.tasks.named("testClasses"))
            val sourceSets = module.extensions.getByType<org.gradle.api.tasks.SourceSetContainer>()
            sourceSets.getByName("test").output.classesDirs.files
                .sortedBy { it.absolutePath }
                .forEach { classDir ->
                    args("--class-dir", "${module.path}=${classDir.absolutePath}")
                }
        }
    }
}

// Both gates examine compiled adapters, inherited methods and both JUnit reports.
fun registerConformanceGuard(name: String, script: String, purpose: String) {
    val guard = tasks.register<Exec>(name) {
        group = "verification"
        description = purpose
        workingDir(rootDir)
        commandLine("python3", script)
    }
    gradle.projectsEvaluated {
        rootProject.subprojects.filter { it.plugins.hasPlugin("java") }.sortedBy { it.path }
            .forEach { module ->
                val sourceSets = module.extensions.getByType<org.gradle.api.tasks.SourceSetContainer>()
                guard.configure {
                    sourceSets.filter { it.name in setOf("main", "test", "testFixtures") }
                        .forEach { sourceSet ->
                            dependsOn(module.tasks.named(sourceSet.classesTaskName))
                            sourceSet.output.classesDirs.files.sortedBy { it.absolutePath }.forEach { dir ->
                                args("--classes", "${module.path}|${sourceSet.name}|${dir.absolutePath}")
                            }
                        }
                    listOf("test", "integrationTest").forEach { tier ->
                        val testTask = module.tasks.named<org.gradle.api.tasks.testing.Test>(tier)
                        dependsOn(testTask)
                        args("--reports", "${module.path}|$tier|${testTask.get().reports.junitXml.outputLocation.get().asFile.absolutePath}")
                    }
                }
            }
    }
}

registerConformanceGuard(
    "checkPreparationHostConformance", "tools/check_preparation_host_conformance.py",
    "Require executed shared preparation host conformance for every production adapter."
)
registerConformanceGuard(
    "checkEffectAuthorityConformance", "tools/check_effect_authority_conformance.py",
    "Require executed shared effect authority conformance for every production adapter."
)

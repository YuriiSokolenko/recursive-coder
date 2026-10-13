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
gradle.projectsEvaluated {
    val javaModules = rootProject.subprojects.filter { it.plugins.hasPlugin("java") }
        .sortedBy { it.path }
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

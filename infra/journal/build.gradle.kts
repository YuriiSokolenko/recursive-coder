plugins { id("recursivecoder.jvm") }

dependencies {
    implementation(project(":core:session"))
    implementation(project(":core:contracts"))
}

/**
 * Real Kotlin 2.4 compiler contract: a C1/C2-only caller cannot reach C3.
 * Fixtures live outside the normal source sets: the negative file must fail
 * with BOTH an unresolved preexecution-host method and a running opt-in error.
 * The dual-authorized positive fixture must compile successfully.
 */
tasks.register("verifyRunningHostOptIn") {
    group = "verification"
    description = "Compile positive and negative trusted-running host opt-in fixtures."
    dependsOn(tasks.named("classes"))
    doLast {
        val compilerClasspath = configurations.getByName("kotlinCompilerClasspath").asPath
        val applicationClasspath = sourceSets["main"].runtimeClasspath.asPath
        val javaBin = file(System.getProperty("java.home") + "/bin/java").absolutePath

        fun compile(fixture: String): Pair<Int, String> {
            val name = fixture.removeSuffix(".kt")
            val output = layout.buildDirectory.dir("running-optin-verification/$name")
                .get().asFile
            output.mkdirs()
            val source = file("src/optInFixtures/$fixture")
            val process = ProcessBuilder(
                javaBin, "-cp", compilerClasspath,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-no-stdlib", "-no-reflect",
                "-classpath", applicationClasspath,
                "-d", output.absolutePath, source.absolutePath
            ).redirectErrorStream(true).start()
            val diagnostics = process.inputStream.bufferedReader().use { it.readText() }
            return process.waitFor() to diagnostics
        }

        val positive = compile("ExplicitRunningOptInCompiles.kt")
        if (positive.first != 0)
            throw GradleException("Authorized running-host Kotlin fixture did not compile:\n" +
                positive.second)

        val negative = compile("PreExecutionOnlyCannotPublishRunning.kt")
        if (negative.first == 0 ||
            !negative.second.contains("TrustedRunningBoundaryApi") ||
            !negative.second.contains("publishAcceptedRunning"))
            throw GradleException("Pre-execution-only caller bypassed C3 compile-time gate " +
                "or failed for an unrelated reason:\n" + negative.second)
        logger.lifecycle("Verified Kotlin C3 host opt-in: positive compiles, " +
            "preexecution-only negative fails on the method and marker")
    }
}

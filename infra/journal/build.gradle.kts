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
        val applicationClasspath = sourceSets["main"].runtimeClasspath.files
            .filter { it.exists() }
            .joinToString(System.getProperty("path.separator")) { it.absolutePath }
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
        // Obtain the opt-in diagnostic from the annotation itself, rather
        // than duplicating its message in this build script.
        val runningBoundarySource = project(":core:session").file(
            "src/main/kotlin/ai/yuriilab/recursivecoder/TrustedRunningBoundaryPolicy.kt"
        ).readText()
        val markerMessage = runningBoundarySource
            .substringAfter("@RequiresOptIn(", "")
            .substringAfter("message = \"", "")
            .substringBefore("\"", "")
            .takeIf { it.isNotEmpty() }
            ?: throw GradleException("Cannot locate TrustedRunningBoundaryApi marker message")

        // Exactly three failures, at the expected source lines: one missing
        // method and two running-boundary opt-in refusals. Extra errors fail
        // closed, even if the intended errors are also present.
        val errors = negative.second.lineSequence()
            .filter { it.contains("error:", ignoreCase = true) }
            .toList()
        fun errorAt(line: Int, matches: (String) -> Boolean): Boolean {
            val location = Regex(
                """PreExecutionOnlyCannotPublishRunning\.kt:$line:\d+:\s*error:""",
                RegexOption.IGNORE_CASE
            )
            return errors.singleOrNull { location.containsMatchIn(it) }
                ?.let(matches) ?: false
        }
        val absentC2Method = errorAt(23) {
            it.contains("unresolved reference", ignoreCase = true) &&
                it.contains("publishAcceptedRunning")
        }
        val deniedC3Construction = errorAt(25) {
            it.contains(markerMessage, ignoreCase = true)
        }
        val deniedC3Invocation = errorAt(27) {
            it.contains(markerMessage, ignoreCase = true)
        }
        if (negative.first == 0 || errors.size != 3 || !absentC2Method ||
            !deniedC3Construction || !deniedC3Invocation)
            throw GradleException("Pre-execution-only caller bypassed C3 compile-time gate " +
                "or failed for an unrelated reason:\n" + negative.second)
        logger.lifecycle("Verified Kotlin C3 host opt-in: positive compiles, " +
            "preexecution-only negative fails on the method and marker")
    }
}

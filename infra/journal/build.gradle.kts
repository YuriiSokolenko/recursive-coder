plugins { id("recursivecoder.jvm") }

dependencies {
    implementation(project(":core:session"))
    implementation(project(":core:contracts"))
}

// The standalone JVM crash fixture must inherit the exact compiled test runtime
// classpath, including transitive dependencies. Gradle's worker bootstrap
// java.class.path is not a reliable source for a child Java process.
listOf("test", "integrationTest").forEach { taskName ->
    tasks.named<Test>(taskName) {
        systemProperty(
            "recursivecoder.fork.classpath",
            sourceSets["test"].runtimeClasspath.asPath
        )
    }
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

        fun compile(fixture: String, extras: List<String> = emptyList()): Pair<Int, String> {
            val name = fixture.removeSuffix(".kt")
            val output = layout.buildDirectory.dir("running-optin-verification/$name")
                .get().asFile
            output.mkdirs()
            val sourcePaths = (listOf(fixture) + extras).map {
                file("src/optInFixtures/$it").absolutePath
            }
            val process = ProcessBuilder(
                listOf(
                    javaBin, "-cp", compilerClasspath,
                    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect",
                    "-classpath", applicationClasspath,
                    "-d", output.absolutePath
                ) + sourcePaths
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
            ?: throw GradleException("Cannot locate trusted running host marker message")

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
        // These are standalone cross-module compilations, not friend modules.
        // A publisher that loses 'internal' must fail this gate even if it
        // retains @TrustedRunningBoundaryApi.
        fun negativeErrors(fixture: String): List<String> {
            val (exit, output) = compile(fixture)
            val errors = output.lineSequence()
                .filter { it.contains("error:", ignoreCase = true) }.toList()
            if (exit == 0 || errors.isEmpty())
                throw GradleException("$fixture unexpectedly compiled or emitted no errors:\n" +
                    output)
            return errors
        }
        fun errorAtSource(fixture: String, marker: String, diagnostic: String): Boolean {
            val line = file("src/optInFixtures/$fixture").readLines()
                .indexOfFirst { it.contains(marker) } + 1
            if (line <= 0) throw GradleException("Missing fixture marker $marker in $fixture")
            return diagnostic.contains("$fixture:$line:") &&
                diagnostic.contains("error:", ignoreCase = true)
        }

        val hiddenFixture = "HiddenRunningPublisherCannotCompile.kt"
        val hiddenErrors = negativeErrors(hiddenFixture)
        val hiddenPublisher = hiddenErrors.any {
            errorAtSource(hiddenFixture, "DurableAcceptedRunningHandoff.publish(", it) &&
                it.contains("DurableAcceptedRunningHandoff") &&
                (it.contains("internal", ignoreCase = true) ||
                    it.contains("invisible", ignoreCase = true) ||
                    it.contains("cannot access", ignoreCase = true))
        }
        if (!hiddenPublisher || hiddenErrors.any {
            !it.contains("$hiddenFixture:") ||
                (!it.contains("DurableAcceptedRunningHandoff") &&
                    !it.contains(markerMessage, ignoreCase = true))
        }) throw GradleException("C3 publisher externally accessible or unrelated errors:\n" +
            hiddenErrors.joinToString("\n"))

        val portFixture = "RunningPortCannotCompile.kt"
        val portErrors = negativeErrors(portFixture)
        if (portErrors.any {
            !it.contains("$portFixture:") || !it.contains(markerMessage, ignoreCase = true)
        } || portErrors.none {
            errorAtSource(portFixture, "host: TrustedSessionRunningHost", it)
        }) throw GradleException("C3 port opt-in gate failed:\n" +
            portErrors.joinToString("\n"))

        val methodFixture = "RunningUseCaseCannotCompile.kt"
        val methodErrors = negativeErrors(methodFixture)
        if (methodErrors.any {
            !it.contains("$methodFixture:") || !it.contains(markerMessage, ignoreCase = true)
        } || methodErrors.none {
            errorAtSource(methodFixture, "runtime.publishTrustedAcceptedRunning(", it)
        }) throw GradleException("C3 runtime method opt-in gate failed:\n" +
            methodErrors.joinToString("\n"))

        // Kotlin allows an annotated override to be called through an unmarked
        // interface. This is a reproducible bypass that the source guard must ban.
        val unsafeOverride = compile("UnmarkedInterfaceDispatchCompiles.kt")
        if (unsafeOverride.first != 0)
            throw GradleException("Unmarked interface dispatch Kotlin fixture changed:\n" +
                unsafeOverride.second)

        // A caller can suppress an opt-in error by diagnostic name without
        // ever referencing the marker. Confirm Kotlin's actual behavior so
        // the architectural source-policy test does not rely on speculation.
        val suppressed = compile("SuppressedRunningOptInCompiles.kt")
        if (suppressed.first != 0)
            throw GradleException("Kotlin C3 suppression behavior changed:\n" +
                suppressed.second)

        // These three K2 expression forms were observed to compile on
        // Kotlin 2.4.21, so their success is a fixed security regression
        // contract. Never silently accept their compilation failures.
        val dynamicSuppressionCases = listOf(
            "UnicodeSuppressedRunningOptInCompiles.kt" to emptyList<String>(),
            "ConcatenatedSuppressedRunningOptInCompiles.kt" to emptyList(),
            "ExternalConstSuppressedRunningOptInCompiles.kt" to
                listOf("ExternalSuppressionKeys.kt")
        )
        dynamicSuppressionCases.forEach { (fixture, extras) ->
            val (status, output) = compile(fixture, extras)
            if (status != 0)
                throw GradleException("K2 C3 bypass compiler behavior changed for $fixture:\n" +
                    output)
            logger.lifecycle("K2 C3 suppression fixture $fixture: suppression COMPILES")
        }

        // Kotlin 2.4.21 does not honor Java's SuppressWarnings for Kotlin C3
        // opt-in errors. Pin that behavior so a later compiler upgrade cannot
        // silently enable a new opt-in bypass. Require the real C3 diagnostics
        // (including a failure at the protected runtime call), not just any
        // compiler failure from a malformed Java annotation.
        val javaFixture = "JavaSuppressWarningsCannotCompile.kt"
        val (javaStatus, javaOutput) = compile(javaFixture)
        val javaErrors = javaOutput.lineSequence().filter {
            it.contains("error:", ignoreCase = true)
        }.toList()
        if (javaStatus == 0 || javaErrors.isEmpty() || javaErrors.any {
                !it.contains("$javaFixture:") ||
                    !it.contains(markerMessage, ignoreCase = true)
            } || javaErrors.none {
                errorAtSource(javaFixture, "runtime.publishTrustedAcceptedRunning(", it)
            }) {
            throw GradleException("Java @SuppressWarnings unexpectedly changed C3 opt-in behavior:\n" +
                javaOutput)
        }
        logger.lifecycle("K2 Java @SuppressWarnings fixture: C3 opt-in correctly refused")

        logger.lifecycle("Verified Kotlin C3 host opt-in: positive compiles, " +
            "negative host, internal publisher, port and runtime gates all refuse")
    }
}

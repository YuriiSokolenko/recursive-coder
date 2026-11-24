package ai.yuriilab.recursivecoder.buildlogic.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArchitectureGraphTest {
    @Test
    fun rejectsUnreviewedTrustedRunningTokensEvenBehindAliasesOrComments() {
        val unreviewed = "core/tasks/src/main/kotlin/Unreviewed.kt"
        val forbidden = listOf(
            "@OptIn(TrustedRunningBoundaryApi::class) class Wrapper",
            "@file:OptIn(TrustedRunningBoundaryApi::class)",
            "@OptIn(OtherMarker::class, /* ) */\n TrustedRunningBoundaryApi::class)",
            "@kotlin.OptIn(ai.yuriilab.recursivecoder.TrustedRunningBoundaryApi::class)",
            "import ai.yuriilab.recursivecoder.TrustedRunningBoundaryApi as T",
            "typealias RunningAlias = TrustedRunningBoundaryApi",
            "import ai.yuriilab.recursivecoder.TrustedRunningBoundaryApi",
            "@Suppress(\"OPT_IN_USAGE_ERROR\") fun unmarkedCaller() {}",
            "@file:Suppress(\"OPT_IN_USAGE\")",
            "@kotlin.Suppress(\"EXPERIMENTAL_API_USAGE_ERROR\")",
            "@Suppress(\"ALL\") fun broadSuppress() {}",
        )
        forbidden.forEach { source ->
            assertTrue(TrustedRunningOptInPolicy.forbiddenInProduction(source, unreviewed), source)
        }
        assertFalse(TrustedRunningOptInPolicy.forbiddenInProduction(
            "@OptIn(OtherMarker::class) class Unrelated", unreviewed))
        assertFalse(TrustedRunningOptInPolicy.forbiddenInProduction(
            """@Suppress("UNCHECKED_CAST") fun harmlessCast() {}""", unreviewed))
        assertTrue(TrustedRunningOptInPolicy.forbiddenInProduction(
            "// OPT_IN_USAGE_ERROR is not used here", // conservative token check
            unreviewed,
        ))
    }

    @Test
    fun suppressionArgumentsMustBeCanonicalSafeLiterals() {
        val unreviewed = "core/tasks/src/main/kotlin/Unsafe.kt"
        val rejected = listOf(
            """@Suppress("OPT_IN_USAGE\u005FERROR") fun escaped() {}""",
            """@Suppress("OPT_IN_" + "USAGE_ERROR") fun concatenated() {}""",
            """@Suppress(OtherFile.C3_ERROR) fun importedConstant() {}""",
            """import kotlin.Suppress as Ignore
               @Ignore(OtherFile.C3_ERROR) fun aliased() {}""",
            """typealias Ignore = kotlin.Suppress
               @Ignore(OtherFile.C3_ERROR) fun typealiasBypass() {}""",
            """@file:kotlin.Suppress(OtherFile.C3_ERROR)""",
            """@Suppress("UNCHECKED_CAST", OtherFile.C3_ERROR) fun mixed() {}""",
            """@Suppress("UNCHECKED\u005FCAST") fun escapedAllowed() {}""",
            """@Suppress(""" + "\"\"\"" + """UNCHECKED_CAST""" + "\"\"\"" + """) fun rawLiteral() {}""",
            """@Suppress("ALL") fun suppressEverything() {}""",
            """@SuppressWarnings("OPT_IN_" + "USAGE_ERROR") fun javaStyle() {}""",
            """@java.lang.SuppressWarnings("OPT_IN_" + "USAGE_ERROR") fun qualified() {}""",
            """import kotlin.Suppress as SuppressWarnings
               @SuppressWarnings("OPT_IN_" + "USAGE_ERROR") fun aliasedJavaStyle() {}""",
            """@Suppress("UNCHECKED_CAST") @Suppress(OtherFile.C3_ERROR) fun chained() {}""",
        )
        rejected.forEach { text ->
            assertTrue(
                TrustedRunningOptInPolicy.forbiddenInProduction(text, unreviewed),
                text,
            )
        }
        val allowed = listOf(
            """fun recordFailure(t: Throwable) = t.addSuppressed(RuntimeException())""",
            """fun recordFailure(t: Throwable) = t.suppressedExceptions""",
            """@Suppress("UNCHECKED_CAST") val safe = 1""",
            """@Suppress("UNUSED_PARAMETER") fun harmless(v: Int) {}""",
            """@file:Suppress("UNCHECKED_CAST", "UNUSED_PARAMETER")""",
            """@kotlin.Suppress(
                  "UNCHECKED_CAST",
                  "UNUSED_PARAMETER"
               ) fun safe() {}""",
        )
        allowed.forEach { text ->
            assertFalse(
                TrustedRunningOptInPolicy.forbiddenInProduction(text, unreviewed),
                text,
            )
        }
    }

    @Test
    fun reviewedDeclarationsRemainAllowedButUnreviewedNewFilesDoNot() {
        val reviewed = mapOf(
            "core/session/src/main/kotlin/ai/yuriilab/recursivecoder/TrustedRunningBoundaryPolicy.kt"
                to "annotation class TrustedRunningBoundaryApi",
            "infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/DurableAcceptedRunningHandoff.kt"
                to "@TrustedRunningBoundaryApi\ninternal object DurableAcceptedRunningHandoff { }",
        )
        reviewed.forEach { (path, source) ->
            assertFalse(TrustedRunningOptInPolicy.forbiddenInProduction(source, path), path)
        }
        assertTrue(TrustedRunningOptInPolicy.forbiddenInProduction(
            "@TrustedRunningBoundaryApi class Unreviewed",
            "infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/NewFile.kt"))
    }

    @Test
    fun reviewedSourceFilesCannotLaunderTrustedRunningOptIn() {
        val reviewed = "infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/DurableSessionEventLedger.kt"
        val permitted = "@TrustedRunningBoundaryApi\ninternal fun appendTrustedAcceptedRunning() {}" +
            "\n@TrustedRunningBoundaryApi\ninternal fun appendTrustedRunningWithFault() {}"
        assertFalse(TrustedRunningOptInPolicy.forbiddenInProduction(permitted, reviewed))

        val bypasses = listOf(
            "@OptIn(TrustedRunningBoundaryApi::class) fun wrapper() {}",
            "@file:OptIn(TrustedRunningBoundaryApi::class)",
            "@kotlin.OptIn(TrustedRunningBoundaryApi::class) fun wrapper() {}",
            "import ai.yuriilab.recursivecoder.TrustedRunningBoundaryApi as Trusted",
            "import ai.yuriilab.recursivecoder.TrustedRunningBoundaryApi",
            "typealias UnsafeAlias = TrustedRunningBoundaryApi",
            "@TrustedRunningBoundaryApi internal fun unreviewedInline() {}",
        )
        bypasses.forEach { bypass ->
            assertTrue(TrustedRunningOptInPolicy.forbiddenInProduction(
                permitted + "\n" + bypass, reviewed,
            ), bypass)
        }
    }

    @Test
    fun rejectsSemanticCompilerOptInsAndAllowsUnrelatedOptions() {
        val marker = "ai.yuriilab.recursivecoder.TrustedRunningBoundaryApi"
        assertTrue(TrustedRunningOptInPolicy.isTrustedMarker(marker))
        assertFalse(TrustedRunningOptInPolicy.isTrustedMarker("com.example.Unrelated"))
        assertFalse(TrustedRunningOptInPolicy.isTrustedMarker(
            "// compilerOptions.optIn.add(\"$marker\")"
        ))
        listOf("-opt-in=$marker", "-Xopt-in=$marker", "-Xuse-experimental=$marker",
            "-opt-in=com.example.Unrelated,$marker").forEach {
            assertTrue(TrustedRunningOptInPolicy.forbiddenFreeCompilerArgs(listOf(it)), it)
        }
        assertTrue(TrustedRunningOptInPolicy.forbiddenFreeCompilerArgs(listOf("-opt-in", marker)))
        assertTrue(TrustedRunningOptInPolicy.forbiddenFreeCompilerArgs(listOf("@c3.args")))
        assertTrue(TrustedRunningOptInPolicy.forbiddenFreeCompilerArgs(listOf(" @/tmp/c3.args ")))
        assertFalse(TrustedRunningOptInPolicy.forbiddenFreeCompilerArgs(
            listOf("-Xjvm-default=all", "-opt-in=com.example.Unrelated")
        ))
    }

    @Test
    fun productionCompilerPluginsAndReflectionFailClosed() {
        listOf("-P", "-Pplugin:sample:flag", "-Xplugin=evil.jar",
            "-Xplugin", "-Xcompiler-plugin=rogue").forEach {
            assertTrue(TrustedRunningOptInPolicy.forbiddenCompilerPluginArguments(listOf(it)), it)
        }
        assertFalse(TrustedRunningOptInPolicy.forbiddenCompilerPluginArguments(
            listOf("-Xjvm-default=all", "-Xno-param-assertions")))
        listOf(
            """Class.forName("hidden.C3")""",
            """host.getDeclaredMethod("publishAcceptedRunning")""",
            """java.lang.reflect.Method""",
            """MethodHandles.lookup()""",
            """DefaultRunSessionUseCase::class.java.methods.single { it.name == "x" }""",
            """DefaultRunSessionUseCase::class.java.declaredMethods""",
            """Thread.currentThread().contextClassLoader.loadClass(name)""",
            """Proxy.newProxyInstance(...)""",
            """System.loadLibrary("unsafe")""",
            """external fun nativePublish()""",
        ).forEach {
            assertTrue(TrustedRunningOptInPolicy.forbiddenDynamicInvocation(it), it)
        }
        assertFalse(TrustedRunningOptInPolicy.forbiddenDynamicInvocation(
            """val className = "no reflection"; failure.addSuppressed(exception)"""))
        val builtIns = listOf(
            "org.jetbrains:annotations:13.0",
            "org.jetbrains.kotlin:kotlin-script-runtime:2.4.21",
            "org.jetbrains.kotlin:kotlin-scripting-common:2.4.21",
            "org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:2.4.21",
            "org.jetbrains.kotlin:kotlin-scripting-compiler-impl-embeddable:2.4.21",
            "org.jetbrains.kotlin:kotlin-scripting-jvm:2.4.21",
            "org.jetbrains.kotlin:kotlin-stdlib:2.4.21",
        )
        // A cache-shaped path is not enough: the SHA-1 directory must match
        // the actual bytes of every artifact, including the baseline modules.
        val home = java.nio.file.Files.createTempDirectory("compiler-plugin-cache").toFile()
        try {
            val paths = builtIns.mapIndexed { index, id ->
                val (group, artifact, version) = id.split(":")
                val bytes = "reviewed-test-artifact-$index".toByteArray(Charsets.UTF_8)
                val sha1 = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(bytes).joinToString("") { byte ->
                        "%02x".format(byte.toInt() and 255)
                    }
                val jar = home.resolve(
                    "caches/modules-2/files-2.1/$group/$artifact/$version/$sha1/$artifact-$version.jar"
                )
                jar.parentFile.mkdirs()
                jar.writeBytes(bytes)
                jar.absolutePath
            }
            assertFalse(TrustedRunningOptInPolicy.forbiddenCompilerPluginClasspath(
                paths, home.absolutePath))
            assertTrue(TrustedRunningOptInPolicy.forbiddenCompilerPluginClasspath(
                paths + "/tmp/rogue.jar", home.absolutePath))
            assertTrue(TrustedRunningOptInPolicy.forbiddenCompilerPluginClasspath(
                paths.dropLast(1), home.absolutePath))
            assertTrue(TrustedRunningOptInPolicy.forbiddenCompilerPluginClasspath(
                paths.dropLast(1) + "/tmp/kotlin-stdlib-2.4.21.jar", home.absolutePath))
            assertTrue(TrustedRunningOptInPolicy.forbiddenCompilerPluginClasspath(
                paths.map { it.replace(home.absolutePath, "/repo/fake/.gradle") },
                home.absolutePath))
            java.io.File(paths[0]).appendText("tampered")
            assertTrue(TrustedRunningOptInPolicy.forbiddenCompilerPluginClasspath(
                paths, home.absolutePath))
        } finally {
            home.deleteRecursively()
        }
        assertEquals(6, TrustedRunningOptInPolicy.approvedSourcePaths.size)
    }

    @Test
    fun reviewedMarkerMustKeepErrorLevelAndBinaryRetention() {
        val path = "core/session/src/main/kotlin/ai/yuriilab/recursivecoder/TrustedRunningBoundaryPolicy.kt"
        val source = """
            @RequiresOptIn(message = "Host only", level = RequiresOptIn.Level.ERROR)
            @Retention(AnnotationRetention.BINARY)
            @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
            annotation class TrustedRunningBoundaryApi
        """.trimIndent()
        assertTrue(TrustedRunningOptInPolicy.reviewedSourceShapeValid(source, path))
        assertTrue(TrustedRunningOptInPolicy.reviewedSourceShapeValid(source + "\n// Harmless edit", path))
        assertFalse(TrustedRunningOptInPolicy.reviewedSourceShapeValid(
            source.replace("Level.ERROR", "Level.WARNING"), path))
        assertFalse(TrustedRunningOptInPolicy.reviewedSourceShapeValid(
            source.replace("AnnotationRetention.BINARY", "AnnotationRetention.SOURCE"), path))
        assertFalse(TrustedRunningOptInPolicy.reviewedSourceShapeValid(
            source.replace("@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)", ""), path))
        assertFalse(TrustedRunningOptInPolicy.reviewedSourceShapeValid(source, "other/" + path))
    }

    @Test
    fun reviewedPathsMustMatchExactlyAndMarkerDeclarationCannotMove() {
        val marker = "annotation class TrustedRunningBoundaryApi"
        val original = "core/session/src/main/kotlin/ai/yuriilab/recursivecoder/TrustedRunningBoundaryPolicy.kt"
        assertFalse(TrustedRunningOptInPolicy.forbiddenInProduction(marker, original))
        assertTrue(TrustedRunningOptInPolicy.forbiddenInProduction(marker, "prefix/" + original))
        assertTrue(TrustedRunningOptInPolicy.forbiddenInProduction(
            marker, "infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/DurableSessionEventLedger.kt"
        ))
    }

    @Test
    fun rejectsMarkedOverrideOfUnmarkedInterface() {
        val source = """
            interface Unmarked { fun publish() }
            class Unsafe: Unmarked {
                @TrustedRunningBoundaryApi
                override fun publish() {}
            }
            fun throughInterface(v: Unmarked) = v.publish()
        """.trimIndent()
        val reviewed = "infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/DurableSessionEventLedger.kt"
        assertTrue(TrustedRunningOptInPolicy.forbiddenInProduction(source, reviewed))
    }

    @Test
    fun acceptsAllowedProductionAndTestEdges() {
        val violations = ArchitectureGraph.violations(
            modules = ArchitectureGraph.allowedProjectDependencies.keys,
            projectEdges = listOf(
                ProjectEdge(":core:session", "implementation", ":core:contracts"),
                ProjectEdge(":app:cli", "testImplementation", ":infra:model"),
                ProjectEdge(":app:cli", "testFixturesImplementation", ":core:tasks"),
            ),
            externalEdges = listOf(ExternalEdge(":core:tasks", "testImplementation", "org.junit.jupiter", "junit-jupiter")),
        )
        assertTrue(violations.isEmpty(), violations.joinToString())
    }

    @Test
    fun fixtureSelfWiringIsNotAProjectCycleButOtherSelfEdgesAreStillIllegal() {
        val modules = ArchitectureGraph.allowedProjectDependencies.keys
        val fixtureEdges = listOf(
            ProjectEdge(":core:contracts", "testFixturesApi", ":core:contracts"),
            ProjectEdge(":core:contracts", "testImplementation", ":core:contracts"),
            ProjectEdge(":core:tasks", "testFixturesApi", ":core:tasks"),
            ProjectEdge(":core:tasks", "testImplementation", ":core:tasks"),
            ProjectEdge(":infra:task-store", "testImplementation", ":core:tasks"),
            ProjectEdge(":app:cli", "testImplementation", ":core:tasks"),
            ProjectEdge(":core:session", "testImplementation", ":core:contracts"),
        )
        assertTrue(ArchitectureGraph.violations(modules, fixtureEdges, emptyList()).isEmpty())
        val illegal = ArchitectureGraph.violations(modules, fixtureEdges + listOf(
            ProjectEdge(":core:contracts", "implementation", ":core:contracts")
        ), emptyList())
        assertTrue(illegal.any { it.contains("Forbidden project dependency") })
        assertTrue(illegal.any { it.contains("Project dependency cycle") })
        val taskSelf = ArchitectureGraph.violations(modules, fixtureEdges + listOf(
            ProjectEdge(":core:tasks", "implementation", ":core:tasks")
        ), emptyList())
        assertTrue(taskSelf.any { it.contains("Forbidden project dependency") })
        assertTrue(taskSelf.any { it.contains("Project dependency cycle") })
    }

    @Test
    fun rejectsForbiddenProductionAndTestDependencies() {
        val violations = ArchitectureGraph.violations(
            modules = ArchitectureGraph.allowedProjectDependencies.keys,
            projectEdges = listOf(
                ProjectEdge(":core:session", "implementation", ":infra:model"),
                ProjectEdge(":core:tasks", "testImplementation", ":infra:workspace"),
            ),
            externalEdges = emptyList(),
        )
        assertTrue(violations.any { it.contains(":core:session:implementation -> :infra:model") })
        assertTrue(violations.any { it.contains(":core:tasks:testImplementation -> :infra:workspace") })
    }

    @Test
    fun taskStoreEdgeIsAllowedButReverseCoreEdgeIsRejected() {
        val allowed = ArchitectureGraph.allowedProjectDependencies.keys
        val permitted = ArchitectureGraph.violations(
            modules = allowed,
            projectEdges = listOf(
                ProjectEdge(":infra:task-store", "implementation", ":core:tasks"),
                ProjectEdge(":infra:task-store", "implementation", ":core:contracts"),
                ProjectEdge(":app:cli", "implementation", ":infra:task-store"),
            ),
            externalEdges = emptyList(),
        )
        assertTrue(permitted.isEmpty(), permitted.joinToString())

        val forbidden = ArchitectureGraph.violations(
            modules = allowed,
            projectEdges = listOf(ProjectEdge(":core:tasks", "testImplementation", ":infra:task-store")),
            externalEdges = emptyList(),
        )
        assertTrue(forbidden.any {
            it.contains("Forbidden project dependency :core:tasks:testImplementation -> :infra:task-store")
        }, forbidden.joinToString())
        assertTrue(forbidden.any { it.contains("Project dependency cycle") }.not())
    }

    @Test
    fun detectsCycleEvenWhenEveryEdgeIsAllowed() {
        val permitted = mapOf(
            ":fixture:a" to setOf(":fixture:b"),
            ":fixture:b" to setOf(":fixture:c"),
            ":fixture:c" to setOf(":fixture:a"),
        )
        val violations = ArchitectureGraph.violations(
            modules = permitted.keys,
            projectEdges = listOf(
                ProjectEdge(":fixture:a", "implementation", ":fixture:b"),
                ProjectEdge(":fixture:b", "testImplementation", ":fixture:c"),
                ProjectEdge(":fixture:c", "testFixturesImplementation", ":fixture:a"),
            ),
            externalEdges = emptyList(),
            allowed = permitted,
        )
        assertEquals(1, violations.size)
        assertTrue(violations.single().contains(
            ":fixture:a -> :fixture:b -> :fixture:c -> :fixture:a",
        ))
        assertTrue(violations.single().contains(":fixture:b:testImplementation -> :fixture:c"))
        assertFalse(violations.single().contains("Forbidden"))
    }

    @Test
    fun rejectsUnclassifiedAndMissingTargetModules() {
        val violations = ArchitectureGraph.violations(
            modules = listOf(":known", ":unclassified"),
            projectEdges = listOf(ProjectEdge(":known", "runtimeOnly", ":missing")),
            externalEdges = emptyList(),
            allowed = mapOf(":known" to setOf(":missing")),
        )
        assertTrue(violations.any { it.contains("Unclassified module :unclassified") })
        assertTrue(violations.any { it.contains("Unknown project dependency target :known:runtimeOnly -> :missing") })
    }

    @Test
    fun unknownForbiddenEdgeIsReportedOnceWithoutHidingOtherViolations() {
        val allowed = mapOf<String, Set<String>>(
            ":core:owner" to emptySet(),
            ":infra:known" to emptySet(),
        )
        val edges = listOf(
            ProjectEdge(":core:owner", "implementation", ":missing"),
            ProjectEdge(":core:owner", "testImplementation", ":infra:known"),
        )
        val external = listOf(
            ExternalEdge(":core:owner", "implementation", "io.ktor", "ktor"),
        )
        val violations = ArchitectureGraph.violations(allowed.keys, edges, external, allowed)
        assertEquals(3, violations.size, violations.joinToString())
        assertEquals(1, violations.count {
            it.startsWith("Unknown project dependency target :core:owner:implementation -> :missing")
        })
        assertTrue(violations.any {
            it.contains("include the module in settings.gradle.kts and update the architecture allowlist")
        })
        assertTrue(violations.any {
            it.startsWith("Forbidden project dependency :core:owner:testImplementation -> :infra:known")
        })
        assertTrue(violations.any {
            it.startsWith("Forbidden core framework dependency :core:owner:implementation -> io.ktor:ktor")
        })
        assertFalse(violations.any {
            it.startsWith("Forbidden project dependency :core:owner:implementation -> :missing")
        })
        assertEquals(violations, ArchitectureGraph.violations(
            allowed.keys.reversed(), edges.reversed(), external.reversed(), allowed,
        ))
    }

    @Test
    fun allowedButMissingTargetStillReportsAnUnknownTargetOnly() {
        val allowed = mapOf(":known" to setOf(":missing"))
        val violations = ArchitectureGraph.violations(
            modules = listOf(":known"),
            projectEdges = listOf(ProjectEdge(":known", "runtimeOnly", ":missing")),
            externalEdges = emptyList(),
            allowed = allowed,
        )
        assertEquals(listOf(
            "Unknown project dependency target :known:runtimeOnly -> :missing " +
                "(include the module in settings.gradle.kts or remove the dependency)",
        ), violations)
    }

    @Test
    fun knownForbiddenTargetKeepsItsAllowlistDiagnostic() {
        val violations = ArchitectureGraph.violations(
            modules = ArchitectureGraph.allowedProjectDependencies.keys,
            projectEdges = listOf(ProjectEdge(":core:tasks", "implementation", ":infra:model")),
            externalEdges = emptyList(),
        )
        assertEquals(listOf(
            "Forbidden project dependency :core:tasks:implementation -> :infra:model " +
                "(allowed: :core:contracts)",
        ), violations)
    }

    @Test
    fun rejectsCoreFrameworkFamiliesWithoutResolvingThem() {
        val violations = ArchitectureGraph.violations(
            modules = listOf(":core:contracts", ":app:cli"),
            projectEdges = emptyList(),
            externalEdges = listOf(
                ExternalEdge(":core:contracts", "testImplementation", "io.ktor.client", "ktor-client"),
                ExternalEdge(":app:cli", "implementation", "io.ktor", "ktor"),
                ExternalEdge(":core:contracts", "implementation", "com.example", "safe"),
            ),
            allowed = mapOf(":core:contracts" to emptySet(), ":app:cli" to emptySet()),
        )
        assertEquals(listOf(
            "Forbidden core framework dependency :core:contracts:testImplementation -> io.ktor.client:ktor-client",
        ), violations)
    }

    @Test
    fun reportsStableCycleAndProblemOrderWithDuplicateEdges() {
        val edges = listOf(
            ProjectEdge(":b", "testImplementation", ":a"),
            ProjectEdge(":a", "implementation", ":b"),
            ProjectEdge(":b", "implementation", ":a"),
        )
        val allowed = mapOf(":a" to setOf(":b"), ":b" to setOf(":a"))
        val original = ArchitectureGraph.violations(listOf(":b", ":a"), edges, emptyList(), allowed)
        val reordered = ArchitectureGraph.violations(listOf(":a", ":b"), edges.reversed(), emptyList(), allowed)
        assertEquals(original, reordered)
        assertEquals(1, original.size)
    }

    @Test
    fun roundTripsEncodedDeclarations() {
        val project = ProjectEdge(":core:tasks", "testImplementation", ":core:contracts")
        val external = ExternalEdge(":core:tasks", "testRuntimeOnly", "org.junit", "junit")
        assertEquals(project, ArchitectureInputCodec.project(
            ArchitectureInputCodec.encode(project.module, project.configuration, project.target),
        ))
        assertEquals(external, ArchitectureInputCodec.external(
            ArchitectureInputCodec.encode(external.module, external.configuration, external.group, external.artifact),
        ))
    }
}

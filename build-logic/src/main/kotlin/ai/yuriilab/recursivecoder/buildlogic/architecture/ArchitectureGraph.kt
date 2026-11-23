package ai.yuriilab.recursivecoder.buildlogic.architecture

data class ProjectEdge(val module: String, val configuration: String, val target: String)
data class ExternalEdge(val module: String, val configuration: String, val group: String, val artifact: String)

/** Pure, deterministic validation of declared dependencies; never resolves artifacts. */
object ArchitectureGraph {
    val allowedProjectDependencies: Map<String, Set<String>> = mapOf(
        ":agent:runtime" to setOf(":core:contracts"),
        ":core:contracts" to emptySet(),
        ":core:session" to setOf(":core:contracts"),
        ":core:tasks" to setOf(":core:contracts"),
        ":infra:model" to setOf(":core:contracts"),
        ":infra:workspace" to setOf(":core:contracts"),
        ":infra:task-store" to setOf(":core:tasks", ":core:contracts"),
        ":infra:journal" to setOf(":core:session", ":core:contracts"),
        ":app:cli" to setOf(
            ":agent:runtime", ":core:contracts", ":core:session", ":core:tasks",
            ":infra:model", ":infra:workspace", ":infra:task-store", ":infra:journal",
        ),
    )

    val forbiddenCoreFrameworkGroups = listOf(
        "ai.koog", "com.github.ajalt.clikt", "io.ktor",
        "com.squareup.okhttp3", "org.apache.httpcomponents",
        "io.netty", "org.eclipse.jetty", "org.springframework",
    )

    fun violations(
        modules: Collection<String>,
        projectEdges: Collection<ProjectEdge>,
        externalEdges: Collection<ExternalEdge>,
        allowed: Map<String, Set<String>> = allowedProjectDependencies,
        forbiddenGroups: Collection<String> = forbiddenCoreFrameworkGroups,
    ): List<String> {
        val paths = modules.toSortedSet()
        val problems = mutableListOf<String>()
        paths.filterNot(allowed::containsKey).forEach {
            problems += "Unclassified module $it; update architecture allowlist"
        }

        // Gradle java-test-fixtures injects these two self references for the
        // core:contracts fixture variant; they are variant wiring, not genuine
        // project-to-project edges or a product dependency cycle.
        // Keep every other self edge (including implementation) forbidden.
        val projects = projectEdges.filterNot { edge ->
            edge.module == ":core:contracts" && edge.target == edge.module &&
                edge.configuration in setOf("testFixturesApi", "testImplementation")
        }.sortedWith(
            compareBy(ProjectEdge::module, ProjectEdge::configuration, ProjectEdge::target),
        )
        projects.forEach { edge ->
            val permitted = allowed[edge.module].orEmpty()
            if (edge.target !in permitted) {
                problems += "Forbidden project dependency ${edge.module}:${edge.configuration} -> " +
                    "${edge.target} (allowed: ${permitted.sorted().joinToString().ifEmpty { "none" }})"
            }
            if (edge.target !in paths) {
                problems += "Unknown project dependency target ${edge.module}:${edge.configuration} -> ${edge.target}"
            }
        }

        externalEdges.sortedWith(
            compareBy(ExternalEdge::module, ExternalEdge::configuration, ExternalEdge::group, ExternalEdge::artifact),
        ).forEach { edge ->
            if (edge.module.startsWith(":core:") &&
                forbiddenGroups.any { edge.group == it || edge.group.startsWith("$it.") }
            ) {
                problems += "Forbidden core framework dependency ${edge.module}:${edge.configuration} -> " +
                    "${edge.group}:${edge.artifact}"
            }
        }

        // Cycle detection is deliberately independent of allowlist validation.
        val outgoing = projects.filter { it.module in paths && it.target in paths }
            .groupBy(ProjectEdge::module)
        val states = mutableMapOf<String, Int>()
        val stack = mutableListOf<String>()
        fun visit(path: String) {
            states[path] = 1
            stack += path
            for (target in outgoing[path].orEmpty().map(ProjectEdge::target).distinct().sorted()) {
                when (states[target]) {
                    1 -> {
                        val cycle = stack.drop(stack.indexOf(target)) + target
                        val annotatedEdges = cycle.zipWithNext().joinToString(", ") { (from, to) ->
                            val config = outgoing.getValue(from).first { it.target == to }.configuration
                            "$from:$config -> $to"
                        }
                        problems += "Project dependency cycle (including tests): " +
                            cycle.joinToString(" -> ") + " [edges: $annotatedEdges]"
                    }
                    2 -> Unit
                    else -> visit(target)
                }
            }
            stack.removeAt(stack.lastIndex)
            states[path] = 2
        }
        paths.forEach { if (states[it] == null) visit(it) }
        return problems.distinct().sorted()
    }
}

/**
 * Host-only C3 source allowlist. Exact checkout-relative paths, never suffixes.
 * A marker in an unreviewed production file is denied even if imported under an alias.
 * Reviewed files may only contain bare @TrustedRunningBoundaryApi usages; they
 * cannot use @OptIn or place the marker on an override of an unmarked interface.
 */
object TrustedRunningOptInPolicy {
    private const val marker = "TrustedRunningBoundaryApi"
    private const val fqMarker = "ai.yuriilab.recursivecoder.TrustedRunningBoundaryApi"
    private const val declarationFile =
        "core/session/src/main/kotlin/ai/yuriilab/recursivecoder/TrustedRunningBoundaryPolicy.kt"
    private const val hostFile =
        "infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/DurableRuntimeAcceptedRunningHost.kt"
    private val approvedTargets = mapOf(
        "core/session/src/main/kotlin/ai/yuriilab/recursivecoder/SessionRuntime.kt" to
            listOf("publishTrustedAcceptedRunning"),
        declarationFile to emptyList(),
        "core/session/src/main/kotlin/ai/yuriilab/recursivecoder/TrustedSessionPreExecutionHost.kt" to
            listOf("TrustedSessionRunningHost"),
        "infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/DurableAcceptedRunningHandoff.kt" to
            listOf("DurableAcceptedRunningHandoff"),
        hostFile to listOf("DurableRuntimeAcceptedRunningHost"),
        "infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/DurableSessionEventLedger.kt" to
            listOf("appendTrustedAcceptedRunning", "appendTrustedRunningWithFault"),
    )

    /** SHA-256 over exact UTF-8 file bytes. Any edit needs a trusted-boundary review. */
    val approvedSourceSha256: Map<String, String> = mapOf(
        "core/session/src/main/kotlin/ai/yuriilab/recursivecoder/SessionRuntime.kt" to
            "d4189bc6c19613efaa50a0503f6dd0116de181bf372ff3f8f603fe0801d6ac3d",
        "core/session/src/main/kotlin/ai/yuriilab/recursivecoder/TrustedRunningBoundaryPolicy.kt" to
            "f06db73cf6e29ae5ce6add2f1e9d4a9b05a3b6e33756367027812fa37687df35",
        "core/session/src/main/kotlin/ai/yuriilab/recursivecoder/TrustedSessionPreExecutionHost.kt" to
            "da9512f54c0b32fe84eda6186d0a7b240dfdb415662f581b8b1ba70efbdd3546",
        "infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/DurableAcceptedRunningHandoff.kt" to
            "740501efc1bcbd90422d47d0d444d8283e2684b16b39453874e9c61bfa0bad25",
        "infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/DurableRuntimeAcceptedRunningHost.kt" to
            "7d89e7fd4a8362efdb6de602f6869f1a692550e863b8a109b5286abac8c4149e",
        "infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/DurableSessionEventLedger.kt" to
            "80aa979779f95c94e75f4e3373e94163b7660d94986e671b968d7ec7c222a898",
    )

    private val reviewedMarkerLine = Regex(
        """\s*(?:@TrustedRunningBoundaryApi|annotation\s+class\s+TrustedRunningBoundaryApi)\s*""",
    )
    // Kotlin permits calling an annotated override through an unannotated interface.
    // No reviewed production file may introduce that new implementation escape hatch.
    private val markedOverride = Regex(
        """@TrustedRunningBoundaryApi\s+(?:(?:@[\w.]+(?:\([^\n)]*\))?|""" +
            """public|private|internal|protected|final|open|suspend|inline|""" +
            """tailrec|operator|infix|external|abstract|actual|expect)\s+)*override\b""",
    )

    private val declarationName = Regex(
        """\b(?:fun\s+interface|annotation\s+class|class|object|fun)\s+([A-Za-z_]\w*)""",
    )

    /**
     * Kotlin diagnostic suppression accepts compile-time constant expressions.
     * Only an explicit, unescaped literal from this small allowlist may appear
     * in a production @Suppress annotation. No arbitrary const, concatenation,
     * unicode escape, spread, named argument, or alias is accepted.
     *
     * After removing every canonical allowed annotation, the remaining source
     * must not contain "Suppress" (including imports/typealiases to it).
     * Conservative token checking intentionally also refuses suspicious text
     * in comments, rather than risk a missed suppression syntax. Also deny
     * the Java-style SuppressWarnings symbol: it may be used as a Kotlin
     * Suppress alias, and its native compiler behavior is independently tested.
     */
    private val allowedSuppress = Regex(
        """@(?:(?:file|field|get|set|property|receiver|param|setparam|delegate):)?""" +
            """(?:kotlin\.)?Suppress\s*\(\s*"(?:UNCHECKED_CAST|UNUSED_PARAMETER)"\s*""" +
            """(?:,\s*"(?:UNCHECKED_CAST|UNUSED_PARAMETER)"\s*)*\)""",
    )
    private val forbiddenDiagnosticName = Regex(
        """\b(?:OPT_IN_USAGE_ERROR|OPT_IN_USAGE|OPT_IN_OVERRIDE_ERROR|""" +
            """OPT_IN_OVERRIDE|EXPERIMENTAL_API_USAGE_ERROR|""" +
            """EXPERIMENTAL_API_USAGE|EXPERIMENTAL_OVERRIDE_ERROR)\b""",
    )

    private val suppressionSymbol = Regex("""\b(?:Suppress|SuppressWarnings)\b""")

    fun forbiddenOptInSuppression(source: String): Boolean =
        suppressionSymbol.containsMatchIn(allowedSuppress.replace(source, "")) ||
            forbiddenDiagnosticName.containsMatchIn(source)

    fun forbiddenInProduction(source: String, sourcePath: String): Boolean {
        if (forbiddenOptInSuppression(source)) return true
        val normalized = sourcePath.replace('\\', '/')
        val expected = approvedTargets[normalized]
        if (expected == null) return marker in source
        // This also detects a reviewed source silently losing its C3 marker.
        val lines = source.lines()
        val annotatedLines = lines.withIndex().filter { it.value.trim() == "@TrustedRunningBoundaryApi" }
        if (annotatedLines.size != expected.size) return true
        val actual = annotatedLines.map { (index, _) ->
            val declaration = lines.drop(index + 1).firstOrNull {
                it.isNotBlank() && !it.trimStart().startsWith("@")
            }.orEmpty()
            if (Regex("""\boverride\b""").containsMatchIn(declaration)) return true
            declarationName.find(declaration)?.groupValues?.get(1).orEmpty()
        }
        if (actual != expected || markedOverride.containsMatchIn(source)) return true
        if (source.lineSequence().any { line ->
                line.contains(marker) &&
                    (!reviewedMarkerLine.matches(line) ||
                        (line.trimStart().startsWith("annotation class") &&
                            normalized != declarationFile))
            }) return true

        // An annotated *class* is still unsafe when one of its interfaces is
        // unmarked: its overridden method can be called via that interface.
        // Freeze the C3 adapter to the one reviewed, marked port and method.
        if (normalized == hostFile) {
            val safeHeader = Regex(
                """(?s)@TrustedRunningBoundaryApi\s+class\s+""" +
                    """DurableRuntimeAcceptedRunningHost\s*\(.*?\)\s*:\s*""" +
                    """TrustedSessionRunningHost\s*\{""",
            )
            if (!safeHeader.containsMatchIn(source)) return true
            val overrides = Regex("""\boverride\s+fun\s+([A-Za-z_]\w*)""")
                .findAll(source).map { it.groupValues[1] }.toList()
            if (overrides != listOf("publishAcceptedRunning")) return true
        }
        // Marking an interface does not make any unmarked superinterface safe.
        if (normalized.endsWith("/TrustedSessionPreExecutionHost.kt") &&
            !Regex("""@TrustedRunningBoundaryApi\s+fun\s+interface\s+TrustedSessionRunningHost\s*\{""")
                .containsMatchIn(source)) return true
        if (normalized.endsWith("/DurableAcceptedRunningHandoff.kt") &&
            !Regex("""@TrustedRunningBoundaryApi\s+internal\s+object\s+DurableAcceptedRunningHandoff\s*\{""")
                .containsMatchIn(source)) return true
        return false
    }

    /** Conservative text-level prohibition; dynamic invocation needs independent review. */
    private val dynamicInvocation = Regex(
        """\b(?:Class\s*\.\s*forName|getMethod|getDeclaredMethod|""" +
            """getMethods|getDeclaredMethods|getConstructors|getDeclaredConstructors|""" +
            """getFields|getDeclaredFields|getConstructor|getDeclaredConstructor|""" +
            """getField|getDeclaredField|setAccessible|trySetAccessible|MethodHandles|""" +
            """java\.lang\.reflect|kotlin\.reflect|java\.beans|""" +
            """java\.lang\.foreign|java\.util\.logging|ObjectInputStream|""" +
            """javax\.(?:script|management)|java\.lang\.instrument|""" +
            """ClassLoader|URLClassLoader|SecureClassLoader|ServiceLoader|""" +
            """java\.lang\.invoke|sun\.misc\.Unsafe|""" +
            """jdk\.internal\.[A-Za-z0-9_.]*Unsafe|ModuleLayer|addOpens|""" +
            """Proxy\s*\.\s*newProxyInstance|""" +
            """System\s*\.\s*load(?:Library)?|external\s+fun)\b""",
    )
    private val kotlinPropertyReflection = Regex(
        """::class\s*\.\s*java\s*\.\s*(?:methods|declaredMethods|""" +
            """constructors|declaredConstructors|fields|declaredFields)\b""",
    )
    private val contextLoaderReflection = Regex("""contextClassLoader\s*\.\s*loadClass\b""")

    fun forbiddenDynamicInvocation(source: String): Boolean =
        dynamicInvocation.containsMatchIn(source) ||
            kotlinPropertyReflection.containsMatchIn(source) ||
            Regex("""::class\s*\.\s*members\b""").containsMatchIn(source) ||
            contextLoaderReflection.containsMatchIn(source)

    /**
     * KGP 2.4.21 supplies these seven compiler-plugin classpath libraries even
     * without optional compiler plugins. Only exact resolved built-in Maven
     * modules may be used, not an extra jar with a familiar filename.
     */
    private val baselineCompilerModules = listOf(
        "org.jetbrains:annotations:13.0",
        "org.jetbrains.kotlin:kotlin-script-runtime:2.4.21",
        "org.jetbrains.kotlin:kotlin-scripting-common:2.4.21",
        "org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:2.4.21",
        "org.jetbrains.kotlin:kotlin-scripting-compiler-impl-embeddable:2.4.21",
        "org.jetbrains.kotlin:kotlin-scripting-jvm:2.4.21",
        "org.jetbrains.kotlin:kotlin-stdlib:2.4.21",
    )

    fun forbiddenCompilerPluginClasspath(paths: List<String>, gradleUserHome: String): Boolean {
        if (paths.size != baselineCompilerModules.size) return true
        val cacheRoot = java.io.File(gradleUserHome).canonicalFile.toPath()
            .resolve("caches/modules-2/files-2.1")
        return baselineCompilerModules.any { id ->
            val (group, artifact, version) = id.split(":")
            val moduleDir = cacheRoot.resolve(group).resolve(artifact).resolve(version)
            val filename = "$artifact-$version.jar"
            paths.count { entry ->
                val resolved = java.io.File(entry).canonicalFile.toPath()
                if (!resolved.startsWith(moduleDir)) return@count false
                val relative = moduleDir.relativize(resolved)
                relative.nameCount == 2 &&
                    relative.getName(0).toString().matches(Regex("[0-9a-f]{40}")) &&
                    relative.getName(1).toString() == filename &&
                    resolved.toFile().isFile &&
                    java.security.MessageDigest.getInstance("SHA-1").let { digest ->
                        resolved.toFile().inputStream().buffered().use { stream ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val bytes = stream.read(buffer)
                                if (bytes < 0) break
                                digest.update(buffer, 0, bytes)
                            }
                        }
                        digest.digest().joinToString("") { byte ->
                            "%02x".format(byte.toInt() and 255)
                        } == relative.getName(0).toString()
                    }
            } != 1
        }
    }

    fun forbiddenCompilerPluginArguments(arguments: List<String>): Boolean =
        arguments.any { raw ->
            val value = raw.trim()
            // Deliberately broad: fail closed for every -P-prefixed switch.
            value == "-P" || value.startsWith("-P") ||
                value == "-Xplugin" || value.startsWith("-Xplugin=") ||
                value == "-Xcompiler-plugin" || value.startsWith("-Xcompiler-plugin=")
        }

    fun isTrustedMarker(value: String): Boolean = value.trim() == fqMarker

    /** Inspect evaluated compiler arguments, not script text or unrelated comments. */
    fun forbiddenFreeCompilerArgs(arguments: List<String>): Boolean =
        arguments.withIndex().any { (index, raw) ->
            val value = raw.trim()
            // kotlinc expands @argfiles *after* Gradle has configured the
            // compiler. Reject them rather than trusting an untracked file.
            if (value.startsWith("@")) return@any true
            val switches = setOf("-opt-in", "-Xopt-in", "-Xuse-experimental")
            val markerList = when {
                switches.any { value.startsWith("$it=") } -> value.substringAfter('=')
                value in switches -> arguments.getOrNull(index + 1).orEmpty()
                else -> ""
            }
            markerList.split(',').any(::isTrustedMarker)
        }
}

/**
 * NUL cannot occur in Gradle project/configuration/dependency names. Encoding records
 * as primitive strings keeps Gradle task inputs portable and configuration-cache safe.
 */
object ArchitectureInputCodec {
    fun encode(vararg fields: String): String = fields.joinToString("\u0000")

    fun project(raw: String): ProjectEdge {
        val parts = decode(raw, 3)
        return ProjectEdge(parts[0], parts[1], parts[2])
    }

    fun external(raw: String): ExternalEdge {
        val parts = decode(raw, 4)
        return ExternalEdge(parts[0], parts[1], parts[2], parts[3])
    }

    private fun decode(raw: String, count: Int): List<String> =
        raw.split('\u0000').also { require(it.size == count) { "Invalid architecture input record" } }
}

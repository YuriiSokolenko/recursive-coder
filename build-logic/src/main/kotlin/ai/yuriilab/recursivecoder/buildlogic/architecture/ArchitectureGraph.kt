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
 * Fail-closed token allowlist for the host-only C3 marker. A raw token check
 * catches Kotlin import aliases, typealiases, multiline/comment tricks and
 * qualified/file-level opt-ins without trusting a regex to parse annotations.
 * Only the reviewed C3 declaration/usage files may carry the bare annotation\n * or declare the marker; no @OptIn wrappers are permitted even within them.
 */
object TrustedRunningOptInPolicy {
    private const val marker = "TrustedRunningBoundaryApi"
    private val allowedProductionPaths = setOf(
        "core/session/src/main/kotlin/ai/yuriilab/recursivecoder/SessionRuntime.kt",
        "core/session/src/main/kotlin/ai/yuriilab/recursivecoder/TrustedRunningBoundaryPolicy.kt",
        "core/session/src/main/kotlin/ai/yuriilab/recursivecoder/TrustedSessionPreExecutionHost.kt",
        "infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/DurableAcceptedRunningHandoff.kt",
        "infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/DurableRuntimeAcceptedRunningHost.kt",
        "infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/DurableSessionEventLedger.kt",
    )

    // The allowlist selects locations, not permissions: even reviewed source
    // files cannot launder the marker through @OptIn, imports or typealiases.
    // Each occurrence must be the bare annotation or its declaration.
    private val reviewedMarkerLine = Regex(
        """\s*(?:@TrustedRunningBoundaryApi|annotation\s+class\s+TrustedRunningBoundaryApi)\s*""",
    )

    fun forbiddenInProduction(source: String, sourcePath: String): Boolean {
        if (!source.contains(marker)) return false
        val normalized = sourcePath.replace('\\', '/')
        if (allowedProductionPaths.none { normalized == it || normalized.endsWith("/$it") })
            return true
        return source.lineSequence().any { line ->
            line.contains(marker) && !reviewedMarkerLine.matches(line)
        }
    }

    /**
     * Inspect root/module build scripts and build-logic convention plugins.
     * A marker reference plus any compiler-wide opt-in configuration is denied,
     * including aliases or values assigned before the compiler option is set.
     * This intentionally errs toward rejection of unusual build script wiring.\n     * Best-effort defence-in-depth only: dynamically composed marker names and\n     * compiler settings outside the scanned Gradle scripts require separate review.
     */
    fun forbiddenCompilerOptIn(script: String): Boolean =
        script.contains(marker) && (
            Regex("""\b(?:compilerOptions|languageSettings|freeCompilerArgs)\b""")
                .containsMatchIn(script) ||
            script.contains("-opt-in")
        )
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

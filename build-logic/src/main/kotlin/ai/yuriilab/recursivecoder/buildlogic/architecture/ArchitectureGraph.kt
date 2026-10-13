package ai.yuriilab.recursivecoder.buildlogic.architecture

data class ProjectEdge(val module: String, val configuration: String, val target: String)
data class ExternalEdge(val module: String, val configuration: String, val group: String, val artifact: String)

/** Pure, deterministic validation of declared dependencies; never resolves artifacts. */
object ArchitectureGraph {
    val allowedProjectDependencies: Map<String, Set<String>> = mapOf(
        ":core:contracts" to emptySet(),
        ":core:session" to setOf(":core:contracts"),
        ":core:tasks" to setOf(":core:contracts"),
        ":infra:model" to setOf(":core:contracts"),
        ":infra:workspace" to setOf(":core:contracts"),
        ":infra:task-store" to setOf(":core:tasks", ":core:contracts"),
        ":infra:journal" to setOf(":core:session", ":core:contracts"),
        ":app:cli" to setOf(
            ":core:contracts", ":core:session", ":core:tasks",
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

        val projects = projectEdges.sortedWith(
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

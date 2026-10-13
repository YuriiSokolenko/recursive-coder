plugins { base }

allprojects {
    group = "ai.yuriilab.recursivecoder"
    version = "0.1.0-SNAPSHOT"
}

// A project dependency is checked in every declared configuration, including tests.
val allowedProjectDependencies = mapOf(
    ":core:contracts" to emptySet<String>(),
    ":core:session" to setOf(":core:contracts"),
    ":core:tasks" to setOf(":core:contracts"),
    ":infra:model" to setOf(":core:contracts"),
    ":infra:workspace" to setOf(":core:contracts"),
    ":app:cli" to setOf(
        ":core:contracts", ":core:session", ":core:tasks",
        ":infra:model", ":infra:workspace"
    )
)
val forbiddenCoreFrameworkGroups = listOf(
    "ai.koog", "com.github.ajalt.clikt", "io.ktor",
    "com.squareup.okhttp3", "org.apache.httpcomponents",
    "io.netty", "org.eclipse.jetty", "org.springframework"
)

tasks.register("checkArchitecture") {
    group = "verification"
    description = "Reject forbidden production/test Gradle dependency edges and cycles."
    doLast {
        val modules = rootProject.allprojects.filter {
            it != rootProject && (it.childProjects.isEmpty() || it.buildFile.isFile)
        }.sortedBy { it.path }
        val problems = mutableListOf<String>()
        val edges = modules.associate { it.path to mutableSetOf<String>() }

        for (module in modules) {
            val allowed = allowedProjectDependencies[module.path]
            if (allowed == null) {
                problems += "Unclassified module " + module.path + "; update architecture allowlist"
            }
            for (configuration in module.configurations.sortedBy { it.name }) {
                for (dependency in configuration.dependencies) {
                    if (dependency is org.gradle.api.artifacts.ProjectDependency) {
                        val target = dependency.path
                        edges.getValue(module.path).add(target)
                        if (target !in allowed.orEmpty()) {
                            problems += "Forbidden project dependency " + module.path + ":" +
                                configuration.name + " -> " + target + " (allowed: " +
                                allowed.orEmpty().sorted().joinToString().ifEmpty { "none" } + ")"
                        }
                    } else if (
                        module.path.startsWith(":core:") &&
                        dependency is org.gradle.api.artifacts.ExternalModuleDependency
                    ) {
                        val externalGroup = dependency.group.orEmpty()
                        if (forbiddenCoreFrameworkGroups.any {
                                externalGroup == it || externalGroup.startsWith("$it.")
                            }) {
                            problems += "Forbidden core framework dependency " + module.path + ":" +
                                configuration.name + " -> " + externalGroup + ":" + dependency.name
                        }
                    }
                }
            }
        }

        val states = mutableMapOf<String, Int>()
        val stack = mutableListOf<String>()
        fun visit(path: String) {
            states[path] = 1
            stack += path
            for (target in edges.getValue(path).sorted()) {
                when (states[target]) {
                    1 -> problems += "Project dependency cycle (including tests): " +
                        (stack.drop(stack.indexOf(target)) + target).joinToString(" -> ")
                    2 -> Unit
                    else -> if (target in edges) visit(target)
                }
            }
            stack.removeAt(stack.lastIndex)
            states[path] = 2
        }
        modules.forEach { if (states[it.path] == null) visit(it.path) }

        if (problems.isNotEmpty()) {
            throw org.gradle.api.GradleException(
                "Architecture boundary violations:\n" +
                    problems.distinct().sorted().joinToString("\n") { " - " + it }
            )
        }
        logger.lifecycle("Architecture boundaries verified for " + modules.size + " modules.")
    }
}

import ai.yuriilab.recursivecoder.buildlogic.architecture.ArchitectureGraph
import ai.yuriilab.recursivecoder.buildlogic.architecture.ArchitectureInputCodec
import ai.yuriilab.recursivecoder.buildlogic.architecture.CheckArchitectureTask
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency

tasks.register<CheckArchitectureTask>("checkArchitecture") {
    group = "verification"
    description = "Reject forbidden production/test Gradle dependency edges and cycles."

    // The entire Gradle model is inspected while configuring the task. Only primitive
    // declared-dependency snapshots are retained for execution/configuration-cache reuse.
    val modules = rootProject.allprojects.filter {
        it != rootProject && (it.childProjects.isEmpty() || it.buildFile.isFile)
    }.sortedBy { it.path }
    modulePaths.set(modules.map { it.path })
    allowedModuleDependencies.set(
        ArchitectureGraph.allowedProjectDependencies.mapValues { (_, targets) -> targets.sorted() },
    )
    forbiddenFrameworkGroups.set(ArchitectureGraph.forbiddenCoreFrameworkGroups)

    val declaredProjects = mutableListOf<String>()
    val declaredExternals = mutableListOf<String>()
    modules.forEach { module ->
        module.configurations.sortedBy { it.name }.forEach { configuration ->
            configuration.dependencies.forEach { dependency ->
                when (dependency) {
                    is ProjectDependency -> declaredProjects += ArchitectureInputCodec.encode(
                        module.path, configuration.name, dependency.path,
                    )
                    is ExternalModuleDependency -> declaredExternals += ArchitectureInputCodec.encode(
                        module.path, configuration.name, dependency.group.orEmpty(), dependency.name,
                    )
                }
            }
        }
    }
    projectEdges.set(declaredProjects.sorted())
    externalEdges.set(declaredExternals.sorted())
}

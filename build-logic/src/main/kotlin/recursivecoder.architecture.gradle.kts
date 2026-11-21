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
    // Track src/main Kotlin inputs so configuration-cache reuse cannot hide
    // an added production opt-in wrapper.
    productionKotlinSources.from(modules.map { module ->
        module.fileTree("src/main").apply { include("**/*.kt") }
    })
    // Include root and module scripts plus precompiled convention plugins.
    // FileTree snapshots both changed scripts and newly added *.gradle.kts
    // on fresh and reused Gradle configuration-cache runs.
    productionGradleScripts.from(rootProject.fileTree(rootProject.rootDir).apply {
        include("**/*.gradle.kts")
        exclude("**/build/**", "**/.gradle/**")
    })
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

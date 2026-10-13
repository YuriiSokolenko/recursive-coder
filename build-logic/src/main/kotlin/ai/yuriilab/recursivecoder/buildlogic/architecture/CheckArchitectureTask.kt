package ai.yuriilab.recursivecoder.buildlogic.architecture

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Reads only snapshotted task inputs at execution time, never Project or Configuration. */
@DisableCachingByDefault(because = "This verification task has no outputs")
abstract class CheckArchitectureTask : DefaultTask() {
    @get:Input abstract val modulePaths: ListProperty<String>
    @get:Input abstract val projectEdges: ListProperty<String>
    @get:Input abstract val externalEdges: ListProperty<String>
    @get:Input abstract val allowedModuleDependencies: MapProperty<String, List<String>>
    @get:Input abstract val forbiddenFrameworkGroups: ListProperty<String>

    @TaskAction
    fun verify() {
        val paths = modulePaths.get()
        val problems = ArchitectureGraph.violations(
            modules = paths,
            projectEdges = projectEdges.get().map(ArchitectureInputCodec::project),
            externalEdges = externalEdges.get().map(ArchitectureInputCodec::external),
            allowed = allowedModuleDependencies.get().mapValues { it.value.toSet() },
            forbiddenGroups = forbiddenFrameworkGroups.get(),
        )
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Architecture boundary violations:\n" +
                    problems.joinToString("\n") { " - $it" },
            )
        }
        logger.lifecycle("Architecture boundaries verified for ${paths.size} modules.")
    }
}

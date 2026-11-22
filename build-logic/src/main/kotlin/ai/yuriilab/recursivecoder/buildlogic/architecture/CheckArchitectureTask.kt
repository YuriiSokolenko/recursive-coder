package ai.yuriilab.recursivecoder.buildlogic.architecture

import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Reads snapshotted inputs only: no Project, SourceSet, or compiler task at execution time. */
@DisableCachingByDefault(because = "This verification task has no outputs")
abstract class CheckArchitectureTask : DefaultTask() {
    @get:Input abstract val modulePaths: ListProperty<String>
    @get:Input abstract val projectEdges: ListProperty<String>
    @get:Input abstract val externalEdges: ListProperty<String>
    @get:Input abstract val allowedModuleDependencies: MapProperty<String, List<String>>
    @get:Input abstract val forbiddenFrameworkGroups: ListProperty<String>
    @get:Input abstract val checkoutRoot: Property<String>
    @get:Input abstract val gradleUserHomeRoot: Property<String>
    @get:Input abstract val invalidProductionRepositories: ListProperty<String>
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val productionKotlinSources: ConfigurableFileCollection
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val productionJavaSources: ConfigurableFileCollection
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val productionResources: ConfigurableFileCollection
    @get:Input abstract val productionCompilerOptIns: MapProperty<String, List<String>>
    @get:Input abstract val productionFreeCompilerArgs: MapProperty<String, List<String>>
    @get:Input abstract val productionLanguageOptIns: MapProperty<String, List<String>>
    @get:Input abstract val productionFileDependencies: ListProperty<String>
    @get:Input abstract val unapprovedProductionCompilers: ListProperty<String>
    @get:Input abstract val productionCompilerPluginClasspath: MapProperty<String, List<String>>
    @get:Input abstract val productionCompilerPluginOptions: MapProperty<String, List<String>>
    @get:Input abstract val productionExtraOutputDirectories: MapProperty<String, List<String>>
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val reviewedC3Sources: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val paths = modulePaths.get()
        val problems = ArchitectureGraph.violations(
            modules = paths,
            projectEdges = projectEdges.get().map(ArchitectureInputCodec::project),
            externalEdges = externalEdges.get().map(ArchitectureInputCodec::external),
            allowed = allowedModuleDependencies.get().mapValues { it.value.toSet() },
            forbiddenGroups = forbiddenFrameworkGroups.get(),
        ).toMutableList()
        val root = File(checkoutRoot.get()).canonicalFile.toPath()
        val pinnedInputs = reviewedC3Sources.files.map { it.canonicalFile.toPath() }.toSet()
        TrustedRunningOptInPolicy.approvedSourceSha256.toSortedMap().forEach { (relative, sha256) ->
            val source = root.resolve(relative).toFile()
            if (source.canonicalFile.toPath() !in pinnedInputs || !source.isFile) {
                problems += "Missing trusted C3 source pin: $relative"
            } else {
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(source.readBytes())
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
                if (actual != sha256)
                    problems += "Trusted C3 source content changed; independent review and pin update required: $relative"
            }
        }

        fun filesWithExtension(inputs: ConfigurableFileCollection, extension: String): List<File> =
            inputs.files.flatMap { input ->
                if (input.isDirectory) input.walkTopDown().filter { it.isFile }.toList()
                else listOf(input).filter { it.isFile }
            }.filter { it.extension.equals(extension, ignoreCase = true) }
                .distinctBy { it.canonicalPath }.sortedBy { it.path }

        fun relativePath(source: File): String? {
            val realPath: Path = source.canonicalFile.toPath()
            return if (realPath.startsWith(root)) {
                root.relativize(realPath).toString().replace('\\', '/')
            } else null
        }

        (filesWithExtension(productionKotlinSources, "kt") +
            filesWithExtension(productionKotlinSources, "kts")).forEach { source ->
            val relative = relativePath(source)
            if (relative == null) {
                problems += "Production Kotlin source outside checkout root: " + source.path
            } else {
                val content = source.readText()
                if (TrustedRunningOptInPolicy.forbiddenDynamicInvocation(content)) {
                    problems += "Forbidden reflection or dynamic class loading in production source: " +
                        source.path
                }
                if (TrustedRunningOptInPolicy.forbiddenOptInSuppression(content)) {
                    problems += "Forbidden Kotlin suppression in production source: " + source.path
                } else if (TrustedRunningOptInPolicy.forbiddenInProduction(content, relative)) {
                    problems += "Unauthorized TrustedRunningBoundaryApi reference in production Kotlin source: " +
                        source.path
                }
            }
        }
        (filesWithExtension(productionResources, "class") +
            filesWithExtension(productionResources, "jar")).forEach { source ->
            problems += "Precompiled JVM bytecode in production resources is forbidden: " +
                source.path
        }
        invalidProductionRepositories.get().forEach { repository ->
            problems += "Unreviewed local/non-central production repository: $repository"
        }
        filesWithExtension(productionJavaSources, "java").forEach { source ->
            problems += "Production Java source is forbidden until a reviewed C3 Java policy exists: " +
                source.path
        }

        unapprovedProductionCompilers.get().forEach { compiler ->
            problems += "Unapproved production JVM compiler: $compiler"
        }
        productionFileDependencies.get().forEach { dependency ->
            problems += "Unreviewed production file dependency: $dependency"
        }
        // KGP provides the same seven artifacts for every JVM compilation;
        // hash each unique classpath once, rather than once per module.
        val compilerClasspathCache = mutableMapOf<List<String>, Boolean>()
        productionCompilerPluginClasspath.get().toSortedMap().forEach { (compilation, jars) ->
            val forbidden = compilerClasspathCache.getOrPut(jars) {
                TrustedRunningOptInPolicy.forbiddenCompilerPluginClasspath(jars, gradleUserHomeRoot.get())
            }
            if (forbidden) problems += "Kotlin compiler plugin classpath is forbidden in $compilation"
        }
        productionCompilerPluginOptions.get().toSortedMap().forEach { (compilation, ids) ->
            if (ids.isNotEmpty())
                problems += "Kotlin compiler plugin options are forbidden in $compilation: " +
                    ids.joinToString(", ")
        }
        productionExtraOutputDirectories.get().toSortedMap().forEach { (sourceSet, dirs) ->
            if (dirs.isNotEmpty())
                problems += "Unreviewed production output.dir bytecode directory in $sourceSet"
        }

        productionCompilerOptIns.get().toSortedMap().forEach { (compilation, optIns) ->
            if (optIns.any(TrustedRunningOptInPolicy::isTrustedMarker))
                problems += "Forbidden compiler-wide TrustedRunningBoundaryApi opt-in in $compilation"
        }
        productionFreeCompilerArgs.get().toSortedMap().forEach { (compilation, args) ->
            val argfileArguments = args.filter { it.trim().startsWith("@") }
            if (argfileArguments.isNotEmpty()) {
                problems += "Compiler @argfile is forbidden in production compilation $compilation"
            }
            if (TrustedRunningOptInPolicy.forbiddenCompilerPluginArguments(args))
                problems += "Forbidden Kotlin compiler plugin argument in $compilation"
            if (TrustedRunningOptInPolicy.forbiddenFreeCompilerArgs(args - argfileArguments.toSet())) {
                problems += "Forbidden compiler-wide TrustedRunningBoundaryApi opt-in in $compilation"
            }
        }
        productionLanguageOptIns.get().toSortedMap().forEach { (sourceSet, optIns) ->
            if (optIns.any(TrustedRunningOptInPolicy::isTrustedMarker))
                problems += "Forbidden compiler-wide TrustedRunningBoundaryApi opt-in in $sourceSet"
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Architecture boundary violations:\n" +
                    problems.distinct().sorted().joinToString("\n") { " - $it" },
            )
        }
        logger.lifecycle("Architecture boundaries verified for ${paths.size} modules.")
    }
}

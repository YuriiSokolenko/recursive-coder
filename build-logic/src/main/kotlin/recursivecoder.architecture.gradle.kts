import ai.yuriilab.recursivecoder.buildlogic.architecture.ArchitectureGraph
import ai.yuriilab.recursivecoder.buildlogic.architecture.ArchitectureInputCodec
import ai.yuriilab.recursivecoder.buildlogic.architecture.CheckArchitectureTask
import ai.yuriilab.recursivecoder.buildlogic.architecture.TrustedRunningOptInPolicy
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// Register only task identity and metadata now. The root task may be realized
// during root-project evaluation, before later subprojects declare dependencies.
val architectureCheck = tasks.register<CheckArchitectureTask>("checkArchitecture") {
    group = "verification"
    description = "Reject forbidden dependency edges, C3 source access and compiler-wide C3 opt-ins."
}

// Gradle's public projectsEvaluated lifecycle callback runs after evaluation of
// every included product project (and its afterEvaluate hooks). Populate the
// same task even if an earlier tasks.named(...).get() has already realized it.
// This is configuration-time model inspection only: the task action receives
// plain snapshotted properties and FileCollections, never mutable Projects.
gradle.projectsEvaluated {
    architectureCheck.configure {
        // Build model inspection takes place during configuration only. Sources are
        // wired as actual compile-task FileCollections, including generated roots.
        // Compiler options are tracked as primitive MapProperty inputs/providers.
        val modules = rootProject.allprojects.filter {
            it != rootProject && (it.childProjects.isEmpty() || it.buildFile.isFile)
        }.sortedBy { it.path }
        modulePaths.set(modules.map { it.path })
        checkoutRoot.set(rootProject.rootDir.canonicalPath)
        gradleUserHomeRoot.set(gradle.gradleUserHomeDir.canonicalPath)
        reviewedC3Sources.from(TrustedRunningOptInPolicy.approvedSourceSha256.keys.map {
            rootProject.file(it)
        })
        allowedModuleDependencies.set(
            ArchitectureGraph.allowedProjectDependencies.mapValues { (_, targets) -> targets.sorted() },
        )
        forbiddenFrameworkGroups.set(ArchitectureGraph.forbiddenCoreFrameworkGroups)

        val declaredProjects = mutableListOf<String>()
        val declaredExternals = mutableListOf<String>()
        val fileDependencies = mutableListOf<String>()
        val unapprovedCompilers = mutableListOf<String>()
        val invalidRepositories = mutableListOf<String>()
        (listOf(rootProject) + modules).forEach { project ->
            project.repositories.forEach { repository ->
                // All reviewed production dependencies resolve via settings Maven Central.
                invalidRepositories += "${project.path}:${repository.name}"
            }
            // Gradle 9.8.2 injects two immutable plugin-resolution repository
            // proxies per project (including root), not MavenArtifactRepository
            // objects. Their origins are authenticated by settings.gradle.kts,
            // where the effective pluginManagement HTTPS URLs are checked.
            // A project buildscript may not add even an allowed Maven URL.
            val gradlePluginRepositories = setOf(
                "__plugin_repository__Gradle Central Plugin Repository",
                "__plugin_repository__MavenRepo",
            )
            val effectiveBuildscriptRepos = project.buildscript.repositories.toList()
            if (effectiveBuildscriptRepos.size != gradlePluginRepositories.size) {
                invalidRepositories += "${project.path}:unexpected buildscript repository count"
            }
            effectiveBuildscriptRepos.forEach { repository ->
                if (repository.name !in gradlePluginRepositories ||
                    repository is org.gradle.api.artifacts.repositories.MavenArtifactRepository) {
                    invalidRepositories += "${project.path}:unreviewed buildscript repository ${repository.name}"
                }
            }
            // Catch explicit project buildscript repositories and dependencies
            // even when an attacker chooses an already-allowed repository URL.
            // This lexical signal complements the effective repository allowlist.
            val buildscriptMutation = Regex(
                """(?m)\b(?:project\s*\.\s*)?buildscript\s*(?:\{|\.\s*repositories\b|\.\s*dependencies\b)""",
            )
            // Script plugins run arbitrary build code during configuration.
            // Refuse the conventional apply(from = ...) and apply { from(...) }
            // syntax as a second lexical tripwire. Dynamically constructed or
            // indirectly applied scripts still require review (not sandboxed).
            val scriptPluginMutation = Regex(
                """\b(?:project\s*\.\s*)?apply\s*(?:\(\s*from\s*=|\{\s*from\s*\()""",
            )
            if (project.buildFile.isFile) {
                val script = project.buildFile.readText()
                if (buildscriptMutation.containsMatchIn(script)) {
                    invalidRepositories += "${project.path}:explicit buildscript repository or dependency mutation"
                }
                if (scriptPluginMutation.containsMatchIn(script)) {
                    invalidRepositories += "${project.path}:unreviewed apply(from) script plugin"
                }
            }
        }
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
                        is FileCollectionDependency -> Unit // evaluated below, including inherited deps
                    }
                }
            }

            // An inherited file dependency may originate in a test*-named
            // configuration and still reach a production classpath.
            module.configurations.filterNot { it.name.startsWith("test", ignoreCase = true) }
                .forEach { configuration ->
                    if (configuration.allDependencies.any { it is FileCollectionDependency }) {
                        fileDependencies += "${module.path}:${configuration.name} (including inherited)"
                    }
                }

            // Test and testFixtures are not production. Any other compilation,
            // including a custom source set, is production until explicitly reviewed.
            fun isTestCompile(name: String): Boolean =
                name in setOf(
                    "compileTestKotlin", "compileTestFixturesKotlin",
                    "compileTestJava", "compileTestFixturesJava",
                    "compileTestGroovy", "compileTestFixturesGroovy",
                    "compileTestScala", "compileTestFixturesScala",
                )

            module.tasks.withType(AbstractCompile::class.java)
                .filterNot { isTestCompile(it.name) || it is JavaCompile }
                .forEach { unapprovedCompilers += "${module.path}:${it.name} (${it.javaClass.name})" }

            module.tasks.withType(KotlinJvmCompile::class.java)
                .filterNot { isTestCompile(it.name) }.sortedBy { it.name }.forEach { compile ->
                    val identity = "${module.path}:${compile.name}"
                    productionKotlinSources.from(compile.sources)
                    // KGP can receive Java source roots alongside Kotlin sources.
                    productionJavaSources.from(compile.sources)
                    productionCompilerOptIns.put(identity, compile.compilerOptions.optIn)
                    productionFreeCompilerArgs.put(identity, compile.compilerOptions.freeCompilerArgs)
                    productionCompilerPluginClasspath.put(
                        identity,
                        compile.pluginClasspath.elements.map { entries ->
                            entries.map { it.asFile.absolutePath }.sorted()
                        },
                    )
                    productionCompilerPluginOptions.put(
                        identity, compile.pluginOptions.map { configs ->
                            configs.flatMap { it.allOptions().keys }.distinct().sorted()
                        },
                    )
                }
            module.tasks.withType(JavaCompile::class.java)
                .filterNot { isTestCompile(it.name) }.sortedBy { it.name }.forEach { compile ->
                    productionJavaSources.from(compile.source)
                }
            module.extensions.findByType(KotlinJvmProjectExtension::class.java)
                ?.sourceSets?.filterNot { it.name in setOf("test", "testFixtures") }
                ?.sortedBy { it.name }?.forEach { sourceSet ->
                    // LanguageSettings still exposes opt-in annotations in KGP 2.4;
                    // use its evaluated values, not a regex over build scripts.
                    productionLanguageOptIns.put(
                        "${module.path}:${sourceSet.name}",
                        sourceSet.languageSettings.optInAnnotationsInUse.sorted(),
                    )
                }
            // The main Java SourceSet contains custom srcDir additions, including
            // generated Java roots that may not yet be present during configuration.
            module.extensions.findByType(SourceSetContainer::class.java)
                ?.filterNot { it.name in setOf("test", "testFixtures") }
                ?.forEach { sourceSet ->
                    productionJavaSources.from(sourceSet.allJava)
                    productionResources.from(sourceSet.resources)
                    // SourceSetOutput.dirs only includes explicitly registered output.dir(...)
                    // paths, not the normal Kotlin/Java compiler output directories.
                    productionExtraOutputDirectories.put(
                        "${module.path}:${sourceSet.name}",
                        sourceSet.output.dirs.elements.map { entries ->
                            entries.map { it.asFile.absolutePath }.sorted()
                        },
                    )
                }
        }
        modules.forEach { project ->
            project.tasks.withType(ProcessResources::class.java)
                .filterNot { it.name in setOf("processTestResources", "processTestFixturesResources") }
                .forEach { process -> productionResources.from(process.inputs.files) }
        }
        invalidProductionRepositories.set(invalidRepositories.sorted())
        projectEdges.set(declaredProjects.sorted())
        externalEdges.set(declaredExternals.sorted())
        productionFileDependencies.set(fileDependencies.sorted())
        unapprovedProductionCompilers.set(unapprovedCompilers.sorted())
        // Commit the seal last, only after all declared task inputs are populated.
        snapshotComplete.set(true)
    }
}

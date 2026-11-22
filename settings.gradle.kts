// Verification applies to plugin, compiler, production and test resolution.
// A missing manifest or an explicit --dependency-verification off/lenient is fatal.
require(
    gradle.startParameter.dependencyVerificationMode ==
        org.gradle.api.artifacts.verification.DependencyVerificationMode.STRICT
) { "Strict Gradle SHA-256 dependency verification is mandatory" }
require(settingsDir.resolve("gradle/verification-metadata.xml").isFile) {
    "Missing reviewed gradle/verification-metadata.xml in ${settingsDir}"
}

pluginManagement {
    includeBuild("build-logic")
    repositories { gradlePluginPortal(); mavenCentral() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
    versionCatalogs {
        create("libs") { from(files("build-logic/gradle/libs.versions.toml")) }
    }
}

rootProject.name = "recursive-coder"
include(":app:cli", ":agent:runtime", ":core:contracts", ":core:session", ":core:tasks",
    ":infra:model", ":infra:workspace", ":infra:task-store", ":infra:journal")

// Fail closed on mavenLocal, flatDir, file: URLs and injected settings repositories.
gradle.settingsEvaluated {
    // Settings-level script plugins can alter repository resolution and trust
    // gates before the project architecture task is configured. Reject the
    // conventional explicit spellings in both settings scripts too.
    val unreviewedScriptPlugin = Regex(
        """\b(?:project\s*\.\s*)?apply\s*(?:\(\s*from\s*=|\{\s*from\s*\()""",
    )
    require(!unreviewedScriptPlugin.containsMatchIn(
        settingsDir.resolve("settings.gradle.kts").readText()
    )) {
        "Unreviewed apply(from) script plugin in settings"
    }
    // Reject legacy settings-level buildscript repositories and classpath injections.
    require(buildscript.repositories.isEmpty() &&
        !buildscript.configurations.any { it.dependencies.isNotEmpty() }) {
        "Unreviewed Gradle settings buildscript repository or classpath"
    }
    fun urls(repositories: org.gradle.api.artifacts.dsl.RepositoryHandler): List<String> =
        repositories.map { repository ->
            val maven = repository as? org.gradle.api.artifacts.repositories.MavenArtifactRepository
                ?: error("Unreviewed Gradle repository in settings: ${repository.name}")
            require(maven.artifactUrls.isEmpty()) {
                "Unreviewed Gradle repository in settings: artifactUrls are forbidden"
            }
            val url = maven.url.toString().trimEnd('/')
            require(maven.url.scheme == "https") {
                "Unreviewed Gradle repository in settings: $url"
            }
            url
        }
    require(urls(dependencyResolutionManagement.repositories) ==
        listOf("https://repo.maven.apache.org/maven2")) {
        "Unreviewed Gradle repository in settings: production resolution must use Maven Central"
    }
    require(urls(pluginManagement.repositories) ==
        listOf("https://plugins.gradle.org/m2", "https://repo.maven.apache.org/maven2")) {
        "Unreviewed Gradle repository in settings: only Plugin Portal and Maven Central allowed"
    }
}

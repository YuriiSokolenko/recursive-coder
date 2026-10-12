pluginManagement {
    includeBuild("build-logic")
    repositories { gradlePluginPortal(); mavenCentral() }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs {
        create("libs") { from(files("build-logic/gradle/libs.versions.toml")) }
    }
}

rootProject.name = "recursive-coder"
include(":app:cli", ":core:contracts", ":core:session", ":core:tasks",
    ":infra:model", ":infra:workspace")

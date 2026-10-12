pluginManagement {
    includeBuild("build-logic")
    repositories { gradlePluginPortal(); mavenCentral() }
}

dependencyResolutionManagement { repositories { mavenCentral() } }

rootProject.name = "recursive-coder"
include(":app:cli", ":core:contracts", ":core:session", ":core:tasks",
    ":infra:model", ":infra:workspace")

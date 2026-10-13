package ai.yuriilab.recursivecoder.buildlogic.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArchitectureGraphTest {
    @Test
    fun acceptsAllowedProductionAndTestEdges() {
        val violations = ArchitectureGraph.violations(
            modules = ArchitectureGraph.allowedProjectDependencies.keys,
            projectEdges = listOf(
                ProjectEdge(":core:session", "implementation", ":core:contracts"),
                ProjectEdge(":app:cli", "testImplementation", ":infra:model"),
                ProjectEdge(":app:cli", "testFixturesImplementation", ":core:tasks"),
            ),
            externalEdges = listOf(ExternalEdge(":core:tasks", "testImplementation", "org.junit.jupiter", "junit-jupiter")),
        )
        assertTrue(violations.isEmpty(), violations.joinToString())
    }

    @Test
    fun rejectsForbiddenProductionAndTestDependencies() {
        val violations = ArchitectureGraph.violations(
            modules = ArchitectureGraph.allowedProjectDependencies.keys,
            projectEdges = listOf(
                ProjectEdge(":core:session", "implementation", ":infra:model"),
                ProjectEdge(":core:tasks", "testImplementation", ":infra:workspace"),
            ),
            externalEdges = emptyList(),
        )
        assertTrue(violations.any { it.contains(":core:session:implementation -> :infra:model") })
        assertTrue(violations.any { it.contains(":core:tasks:testImplementation -> :infra:workspace") })
    }

    @Test
    fun taskStoreEdgeIsAllowedButReverseCoreEdgeIsRejected() {
        val allowed = ArchitectureGraph.allowedProjectDependencies.keys
        val permitted = ArchitectureGraph.violations(
            modules = allowed,
            projectEdges = listOf(
                ProjectEdge(":infra:task-store", "implementation", ":core:tasks"),
                ProjectEdge(":infra:task-store", "implementation", ":core:contracts"),
                ProjectEdge(":app:cli", "implementation", ":infra:task-store"),
            ),
            externalEdges = emptyList(),
        )
        assertTrue(permitted.isEmpty(), permitted.joinToString())

        val forbidden = ArchitectureGraph.violations(
            modules = allowed,
            projectEdges = listOf(ProjectEdge(":core:tasks", "testImplementation", ":infra:task-store")),
            externalEdges = emptyList(),
        )
        assertTrue(forbidden.any {
            it.contains("Forbidden project dependency :core:tasks:testImplementation -> :infra:task-store")
        }, forbidden.joinToString())
        assertTrue(forbidden.any { it.contains("Project dependency cycle") }.not())
    }

    @Test
    fun detectsCycleEvenWhenEveryEdgeIsAllowed() {
        val permitted = mapOf(
            ":fixture:a" to setOf(":fixture:b"),
            ":fixture:b" to setOf(":fixture:c"),
            ":fixture:c" to setOf(":fixture:a"),
        )
        val violations = ArchitectureGraph.violations(
            modules = permitted.keys,
            projectEdges = listOf(
                ProjectEdge(":fixture:a", "implementation", ":fixture:b"),
                ProjectEdge(":fixture:b", "testImplementation", ":fixture:c"),
                ProjectEdge(":fixture:c", "testFixturesImplementation", ":fixture:a"),
            ),
            externalEdges = emptyList(),
            allowed = permitted,
        )
        assertEquals(1, violations.size)
        assertTrue(violations.single().contains(
            ":fixture:a -> :fixture:b -> :fixture:c -> :fixture:a",
        ))
        assertTrue(violations.single().contains(":fixture:b:testImplementation -> :fixture:c"))
        assertFalse(violations.single().contains("Forbidden"))
    }

    @Test
    fun rejectsUnclassifiedAndMissingTargetModules() {
        val violations = ArchitectureGraph.violations(
            modules = listOf(":known", ":unclassified"),
            projectEdges = listOf(ProjectEdge(":known", "runtimeOnly", ":missing")),
            externalEdges = emptyList(),
            allowed = mapOf(":known" to setOf(":missing")),
        )
        assertTrue(violations.any { it.contains("Unclassified module :unclassified") })
        assertTrue(violations.any { it.contains("Unknown project dependency target :known:runtimeOnly -> :missing") })
    }

    @Test
    fun rejectsCoreFrameworkFamiliesWithoutResolvingThem() {
        val violations = ArchitectureGraph.violations(
            modules = listOf(":core:contracts", ":app:cli"),
            projectEdges = emptyList(),
            externalEdges = listOf(
                ExternalEdge(":core:contracts", "testImplementation", "io.ktor.client", "ktor-client"),
                ExternalEdge(":app:cli", "implementation", "io.ktor", "ktor"),
                ExternalEdge(":core:contracts", "implementation", "com.example", "safe"),
            ),
            allowed = mapOf(":core:contracts" to emptySet(), ":app:cli" to emptySet()),
        )
        assertEquals(listOf(
            "Forbidden core framework dependency :core:contracts:testImplementation -> io.ktor.client:ktor-client",
        ), violations)
    }

    @Test
    fun reportsStableCycleAndProblemOrderWithDuplicateEdges() {
        val edges = listOf(
            ProjectEdge(":b", "testImplementation", ":a"),
            ProjectEdge(":a", "implementation", ":b"),
            ProjectEdge(":b", "implementation", ":a"),
        )
        val allowed = mapOf(":a" to setOf(":b"), ":b" to setOf(":a"))
        val original = ArchitectureGraph.violations(listOf(":b", ":a"), edges, emptyList(), allowed)
        val reordered = ArchitectureGraph.violations(listOf(":a", ":b"), edges.reversed(), emptyList(), allowed)
        assertEquals(original, reordered)
        assertEquals(1, original.size)
    }

    @Test
    fun roundTripsEncodedDeclarations() {
        val project = ProjectEdge(":core:tasks", "testImplementation", ":core:contracts")
        val external = ExternalEdge(":core:tasks", "testRuntimeOnly", "org.junit", "junit")
        assertEquals(project, ArchitectureInputCodec.project(
            ArchitectureInputCodec.encode(project.module, project.configuration, project.target),
        ))
        assertEquals(external, ArchitectureInputCodec.external(
            ArchitectureInputCodec.encode(external.module, external.configuration, external.group, external.artifact),
        ))
    }
}

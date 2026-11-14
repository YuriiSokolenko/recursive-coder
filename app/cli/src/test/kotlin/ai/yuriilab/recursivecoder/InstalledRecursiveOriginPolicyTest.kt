package ai.yuriilab.recursivecoder

import java.time.Instant
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Unit-only policy tests; the real Linux host still authenticates its lease. */
@Tag("unit")
class InstalledRecursiveOriginPolicyTest {
    private val sessionId = "origin-unit-session"
    private val graphId = GraphId("origin-parent-graph")
    private val stageId = StageRunId("origin-parent-develop")
    private val target = FiveRoleArtifactTarget(
        ArtifactId("origin-implementation"), "src/main/OriginManifest.xml")
    private val targets = mapOf(StageKind.DEVELOP to target)
    private val budget = StageBudget(10_000, 120_000, 1, 1)
    private val graphCorrelation = GraphCorrelation(sessionId, graphId,
        "origin-parent-correlation")
    private val stageCorrelation = StageCorrelation(
        sessionId, graphId, stageId, "origin-request", "origin-stage-correlation")
    private val stage = StageNode(stageCorrelation, StageKind.DEVELOP,
        StageState.RUNNING, AgentId("origin-developer-actor"))
    private val graph = StageGraph(graphCorrelation, StageGraphState.RUNNING,
        listOf(stage), emptyList(), budget)
    private val snapshot = PreparationStageSnapshot(graph, emptySet())
    private val model = ModelConfig("fake", "fake-v1", "http://localhost/unused",
        "env:FAKE", 4096, 512, setOf("tool_calls"), null, 1000, 1000)
    private val input = DeveloperFileTarget("src/main/Origin.kt")

    private fun request(
        providedGraph: StageGraph = graph.copy(stages = listOf(
            stage.copy(state = StageState.READY))),
        requestedId: StageRunId = stageId,
        requestId: String = stageCorrelation.requestId,
        role: EngineeringRole = EngineeringRole.DEVELOPER
    ) = StageRoleRequest(providedGraph, requestedId, requestId, role,
        emptySet(), model, "Implement", Instant.MAX, Cancellation())

    private fun projected(frozen: Set<String> = emptySet()) =
        InstalledRecursiveOriginPolicy.project(sessionId, stage, graphId,
            frozen, targets, listOf(input), budget)

    @Test fun validReadyRequestCanProjectExactRunningHostStage() {
        assertEquals(stage, InstalledRecursiveOriginPolicy.liveStage(
            request(), StageKind.DEVELOP, snapshot, sessionId))
        val grant = assertNotNull(projected())
        assertEquals(stageId, grant.stageRunId)
        assertEquals(StageKind.DEVELOP,
            grant.graph.stages.single().kind)
        assertEquals(setOf(input.path, target.path), grant.writablePaths)
        assertEquals(StageGateDecision.Permit,
            StageGraphPolicy.validate(grant.graph))
    }

    @Test fun fakeOrStaleParentIdentityCannotCreateLiveOrigin() {
        assertNull(InstalledRecursiveOriginPolicy.liveStage(
            request(requestedId = StageRunId("unknown-stage")),
            StageKind.DEVELOP, snapshot, sessionId))
        assertNull(InstalledRecursiveOriginPolicy.liveStage(
            request(requestId = "spoofed-request"),
            StageKind.DEVELOP, snapshot, sessionId))
        assertNull(InstalledRecursiveOriginPolicy.liveStage(
            request(role = EngineeringRole.SDD),
            StageKind.DEVELOP, snapshot, sessionId))
        assertNull(InstalledRecursiveOriginPolicy.liveStage(
            request(providedGraph = graph),
            StageKind.DEVELOP, snapshot, sessionId))
        assertNull(InstalledRecursiveOriginPolicy.liveStage(
            request(providedGraph = graph.copy(correlation =
                graphCorrelation.copy(correlationId = "spoofed-correlation"))),
            StageKind.DEVELOP, snapshot, sessionId))
        assertNull(InstalledRecursiveOriginPolicy.liveStage(
            request(), StageKind.DEVELOP, snapshot, "other-session"))
    }

    @Test fun projectedGrantCannotOverlapFrozenParentInputs() {
        assertNull(projected(frozen = setOf(target.path)))
        assertNull(projected(frozen = setOf(input.path)))
        val grant = assertNotNull(projected(frozen = setOf("specs/Frozen.md")))
        assertEquals(setOf("specs/Frozen.md"), grant.frozenPaths)
        assertEquals(StageGateDecision.Permit,
            StageGraphPolicy.validate(grant.graph))
    }

    @Test fun wrongRoleOrMissingOutputTargetFailsClosed() {
        assertNull(InstalledRecursiveOriginPolicy.project(sessionId,
            stage.copy(kind = StageKind.REVIEW), graphId, emptySet(),
            targets, listOf(input), budget))
        assertNull(InstalledRecursiveOriginPolicy.project(sessionId,
            stage, graphId, emptySet(), emptyMap(), listOf(input), budget))
    }
}

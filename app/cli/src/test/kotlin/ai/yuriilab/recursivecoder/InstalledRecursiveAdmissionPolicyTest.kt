package ai.yuriilab.recursivecoder

import java.time.Instant
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pure admission tests; installed Linux CLI E2E asserts no physical child writes. */
@Tag("unit")
class InstalledRecursiveAdmissionPolicyTest {
    private val now = Instant.parse("2026-11-14T12:00:00Z")
    private val limits = RecursiveLimits(6, 1, 120_000,
        now.plusSeconds(180))

    private fun grant(kind: StageKind = StageKind.DEVELOP,
        depth: Int = 1, children: Int = 1): RecursiveRootGrant {
        val graphId = GraphId("admission-unit-graph")
        val id = StageRunId("admission-unit-stage")
        val stage = StageNode(StageCorrelation("admission-session", graphId,
            id, "admission-request", "admission-correlation"), kind,
            StageState.RUNNING, AgentId("admission-actor"))
        val graph = StageGraph(GraphCorrelation("admission-session", graphId,
            "admission-graph-correlation"), StageGraphState.RUNNING,
            listOf(stage), emptyList(), StageBudget(10_000, 120_000, depth, children))
        val scope = if (kind == StageKind.SDD)
            setOf("specs/Admission.md") else setOf("src/main/Admission.kt")
        return RecursiveRootGrant(graph, id, "origin-task-" + kind.name.lowercase(),
            scope, emptySet())
    }

    private fun result(kind: StageKind = StageKind.DEVELOP,
        grant: RecursiveRootGrant = grant(kind),
        cyclic: Boolean = false, tokens: Long = 500,
        maxToolCalls: Long = 3,
        limits: RecursiveLimits = this.limits): String? =
        InstalledRecursiveAdmissionPolicy.preflight(kind, grant,
            "nested-" + kind.name.lowercase(), cyclic, tokens, maxToolCalls,
            limits, now)

    @Test fun bothRolesAcceptBoundedPolicyOnlyGrants() {
        assertNull(result())
        assertNull(result(kind = StageKind.SDD))
    }

    @Test fun selfDependencyIsRejectedBeforeExecution() {
        assertEquals("CYCLIC_RECURSIVE_GRAPH", result(cyclic = true))
        assertEquals("CYCLIC_RECURSIVE_GRAPH",
            result(kind = StageKind.SDD, cyclic = true))
    }

    @Test fun depthTokenToolAndGlobalLimitsAreEnforced() {
        assertEquals("DEPTH_LIMIT", result(grant = grant(depth = 0)))
        assertEquals("TOKEN_BUDGET_EXHAUSTED", result(tokens = 10_001))
        assertEquals("TOOL_BUDGET_EXHAUSTED",
            result(limits = limits.copy(maxToolCalls = 2)))
        assertEquals("GLOBAL_CHILD_LIMIT",
            result(limits = limits.copy(maxTotalChildren = 0)))
    }

    @Test fun expiryDeniesBeforeAnyChildCheckoutCanStart() {
        assertEquals("GLOBAL_DEADLINE",
            result(limits = limits.copy(deadline = now)))
    }
}

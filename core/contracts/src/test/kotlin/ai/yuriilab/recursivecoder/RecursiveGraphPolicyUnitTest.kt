package ai.yuriilab.recursivecoder

import java.time.Instant
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** No coroutines, clock, network, filesystem, model or Koog: pure policy matrix. */
@Tag("unit")
class RecursiveGraphPolicyUnitTest {
    private val now = Instant.parse("2026-11-12T12:00:00Z")
    private val rootId = GraphId("root-78")
    private val rootStage = StageRunId("stage-root-78")
    private val budget = StageBudget(100, 10_000, 2, 2)
    private val limits = RecursiveLimits(10, 4, 5_000, now.plusSeconds(20))

    private fun root(origin: StageKind = StageKind.DEVELOP): RecursiveNode = RecursiveNode(
        graphId = rootId, stageRunId = rootStage, parentGraphId = null,
        parentStageRunId = null, requestId = null, origin = origin, taskId = "root-task",
        dependencies = emptySet(), depth = 0,
        writablePaths = if (origin == StageKind.REVIEW) emptySet() else setOf(path(origin)),
        frozenPaths = setOf("specs/Frozen.md"),
        allowedReadTools = setOf("read", "list"),
        granted = RecursiveUsage(100, 10),
        deadline = now.plusSeconds(10),
        state = RecursiveNodeState.RUNNING
    )

    private fun path(stage: StageKind): String = when (stage) {
        StageKind.SDD -> "specs/Child.md"
        StageKind.ATDD -> "tests/acceptance/Child.feature"
        StageKind.TDD -> "tests/unit/ChildTest.kt"
        StageKind.DEVELOP -> "src/main/Child.kt"
        StageKind.REVIEW -> "specs/Child.md"
    }

    private fun proposal(
        origin: StageKind = StageKind.DEVELOP, requestId: String = "req-1",
        taskId: String = "child-task", tokens: Long = 10, tools: Long = 2,
        duration: Long = 1_000, paths: Set<String> = emptySet(),
        reads: Set<String> = emptySet(), dependencies: Set<GraphId> = emptySet(),
        parentId: GraphId = rootId, stageId: StageRunId = rootStage
    ) = RecursiveChildProposal(requestId, parentId, stageId, origin, taskId,
        dependencies, paths, reads, tokens, tools, duration)

    private fun deny(decision: RecursiveDecision): String =
        assertIs<RecursiveDecision.Denied>(decision).code

    private fun child(snapshot: RecursiveSnapshot, proposal: RecursiveChildProposal):
        Pair<RecursiveSnapshot, RecursiveNode> {
        val accepted = assertIs<RecursiveDecision.Allowed>(
            RecursiveGraphPolicy.admit(snapshot, rootId, proposal, limits, budget, now))
        val parent = snapshot.node(rootId)!!
        return snapshot.copy(nodes = snapshot.nodes.map {
            if (it.graphId == rootId) it.copy(delegated = RecursiveUsage(
                it.delegated.tokens + accepted.child.granted.tokens,
                it.delegated.toolCalls + accepted.child.granted.toolCalls)) else it
        } + accepted.child) to accepted.child
    }

    @Test fun validNestedGraphAndStableIds() {
        val start = RecursiveSnapshot(listOf(root()))
        val (one, first) = child(start, proposal())
        assertEquals(RecursiveGraphPolicy.childId(rootId, rootStage, "req-1"), first.graphId)
        assertEquals(RecursiveGraphPolicy.childStageId(first.graphId), first.stageRunId)
        assertEquals(RecursiveGraphPolicy.childId(rootId, rootStage, "req-1"),
            RecursiveGraphPolicy.childId(rootId, rootStage, "req-1"))
        val running = one.copy(nodes = one.nodes.map {
            if (it.graphId == first.graphId) it.copy(state = RecursiveNodeState.RUNNING)
            else it
        })
        val nested = proposal(requestId = "req-2", taskId = "grandchild",
            origin = StageKind.DEVELOP, tokens = 5, tools = 1,
            parentId = first.graphId, stageId = first.stageRunId)
        val next = assertIs<RecursiveDecision.Allowed>(
            RecursiveGraphPolicy.admit(running, first.graphId, nested, limits, budget, now))
        assertEquals(2, next.child.depth)
        assertEquals(StageKind.DEVELOP, next.child.origin)
        assertEquals(first.graphId, next.child.parentGraphId)
    }

    @Test fun graphTopologyRejectsCyclesAndBrokenReferences() {
        val start = RecursiveSnapshot(listOf(root()))
        val (one, first) = child(start, proposal())
        val (two, second) = child(one, proposal(StageKind.DEVELOP, "req-2", "other-task"))
        val cycle = two.copy(nodes = two.nodes.map {
            when (it.graphId) {
                first.graphId -> it.copy(dependencies = setOf(second.graphId))
                second.graphId -> it.copy(dependencies = setOf(first.graphId))
                else -> it
            }
        })
        assertEquals("CYCLIC_RECURSIVE_GRAPH",
            deny(RecursiveGraphPolicy.validate(cycle, limits, budget)))
        val broken = two.copy(nodes = two.nodes.map {
            if (it.graphId == first.graphId) it.copy(parentStageRunId = StageRunId("wrong-parent-stage")) else it
        })
        assertEquals("CHILD_AUTHORITY_ESCALATION",
            deny(RecursiveGraphPolicy.validate(broken, limits, budget)))
        val duplicate = two.copy(nodes = two.nodes + first)
        assertEquals("DUPLICATE_GRAPH_ID",
            deny(RecursiveGraphPolicy.validate(duplicate, limits, budget)))
    }

    @Test fun orphanRootReservationDependenciesAndStateFailClosed() {
        val start = RecursiveSnapshot(listOf(root()))
        val (one, first) = child(start, proposal())

        assertEquals("INVALID_ROOT", deny(RecursiveGraphPolicy.validate(
            RecursiveSnapshot(listOf(root().copy(
                parentStageRunId = StageRunId("not-null")))), limits, budget)))
        assertEquals("ORPHAN_GRAPH", deny(RecursiveGraphPolicy.validate(
            one.copy(nodes = one.nodes.map {
                if (it.graphId == first.graphId) it.copy(parentGraphId = GraphId("absent"))
                else it
            }), limits, budget)))
        assertEquals("INVALID_CHILD_RESERVATION", deny(RecursiveGraphPolicy.validate(
            one.copy(nodes = one.nodes.map {
                if (it.graphId == rootId) it.copy(delegated = RecursiveUsage())
                else it
            }), limits, budget)))
        assertEquals("INVALID_CHILD_DEPENDENCY", deny(RecursiveGraphPolicy.validate(
            one.copy(nodes = one.nodes.map {
                if (it.graphId == first.graphId) it.copy(dependencies = setOf(
                    GraphId("absent-sibling"))) else it
            }), limits, budget)))
        assertEquals("INVALID_RECURSIVE_STATE", deny(RecursiveGraphPolicy.validate(
            one.copy(nodes = one.nodes.map {
                if (it.graphId == rootId) it.copy(state = RecursiveNodeState.SUCCEEDED)
                else it
            }), limits, budget)))
        assertEquals("INVALID_RECURSIVE_LIMITS", deny(RecursiveGraphPolicy.validate(
            start, limits.copy(maxTotalChildren = RecursiveGraphPolicy.MAX_TOTAL_CHILDREN + 1),
            budget)))
    }

    @Test fun noProgressAndRepeatRequestsFailClosed() {
        val (one, _) = child(RecursiveSnapshot(listOf(root())), proposal())
        assertEquals("REPEATED_REQUEST", deny(RecursiveGraphPolicy.admit(
            one, rootId, proposal(), limits, budget, now)))
        assertEquals("NO_PROGRESS_LOOP", deny(RecursiveGraphPolicy.admit(
            one, rootId, proposal(requestId = "req-3"), limits, budget, now)))
        assertEquals("NO_PROGRESS_LOOP", deny(RecursiveGraphPolicy.admit(
            one, rootId, proposal(requestId = "req-4", taskId = "root-task"),
            limits, budget, now)))
    }

    @Test fun depthCountAndGlobalCeilings() {
        val start = RecursiveSnapshot(listOf(root()))
        val (one, first) = child(start, proposal())
        val running = one.copy(nodes = one.nodes.map {
            if (it.graphId == first.graphId) it.copy(state = RecursiveNodeState.RUNNING)
            else it
        })
        val (two, second) = child(running, proposal(requestId = "req-2", taskId = "second"))
        assertEquals("CHILD_COUNT_LIMIT", deny(RecursiveGraphPolicy.admit(
            two, rootId, proposal(requestId = "req-3", taskId = "third"),
            limits, budget, now)))
        val depthBudget = budget.copy(maxDepth = 1)
        assertEquals("DEPTH_LIMIT", deny(RecursiveGraphPolicy.admit(
            running, first.graphId, proposal(requestId = "req-nested",
                taskId = "deeper", parentId = first.graphId, stageId = first.stageRunId),
            limits, depthBudget, now)))
        assertEquals("GLOBAL_CHILD_LIMIT", deny(RecursiveGraphPolicy.admit(
            two, rootId, proposal(requestId = "req-overflow", taskId = "overflow"),
            limits.copy(maxTotalChildren = 2), budget.copy(maxChildren = 3), now)))
        assertTrue(second.depth == 1)
    }

    @Test fun budgetsChargeMonotonicallyAndRejectOverspend() {
        val start = RecursiveSnapshot(listOf(root()))
        assertEquals("TOKEN_BUDGET_EXHAUSTED", deny(RecursiveGraphPolicy.admit(
            start, rootId, proposal(tokens = 101), limits, budget, now)))
        assertEquals("TOOL_BUDGET_EXHAUSTED", deny(RecursiveGraphPolicy.admit(
            start, rootId, proposal(tools = 11), limits, budget, now)))
        val (reserved, _) = child(start, proposal(tokens = 95, tools = 9))
        val charged = assertIs<RecursiveDecision.Allowed>(
            RecursiveGraphPolicy.authorizeCharge(reserved.node(rootId)!!, RecursiveUsage(5, 1)))
        assertEquals(RecursiveUsage(5, 1), charged.child.spent)
        assertEquals("TOKEN_BUDGET_EXHAUSTED", deny(RecursiveGraphPolicy.authorizeCharge(
            charged.child, RecursiveUsage(1, 0))))
        assertEquals("TOOL_BUDGET_EXHAUSTED", deny(RecursiveGraphPolicy.authorizeCharge(
            charged.child, RecursiveUsage(0, 1))))
        assertEquals("INVALID_USAGE", deny(RecursiveGraphPolicy.authorizeCharge(
            charged.child, RecursiveUsage(-1, 0))))
        assertEquals(RecursiveUsage(95, 9), charged.child.delegated)
    }

    @Test fun deadlineDurationCancellationAndOrigin() {
        val start = RecursiveSnapshot(listOf(root()))
        assertEquals("STAGE_DURATION_LIMIT", deny(RecursiveGraphPolicy.admit(start, rootId,
            proposal(duration = 5_001), limits, budget, now)))
        assertEquals("STAGE_DURATION_LIMIT", deny(RecursiveGraphPolicy.admit(start, rootId,
            proposal(duration = 2_000), limits, budget, now.plusSeconds(9))))
        assertEquals("GLOBAL_DEADLINE", deny(RecursiveGraphPolicy.admit(start, rootId,
            proposal(), limits, budget, now.plusSeconds(20))))
        assertEquals("STAGE_DEADLINE", deny(RecursiveGraphPolicy.admit(start, rootId,
            proposal(), limits, budget, now.plusSeconds(10))))
        assertEquals("PARENT_ORIGIN_MISMATCH", deny(RecursiveGraphPolicy.admit(start, rootId,
            proposal(origin = StageKind.SDD), limits, budget, now)))
        assertEquals("PARENT_NOT_RUNNING", deny(RecursiveGraphPolicy.admit(
            RecursiveSnapshot(listOf(root().copy(state = RecursiveNodeState.CANCELLED))),
            rootId, proposal(), limits, budget, now)))
    }

    @Test fun permissionScopesNeverEscalateOrUnfreeze() {
        val start = RecursiveSnapshot(listOf(root()))
        assertEquals("CHILD_AUTHORITY_ESCALATION", deny(RecursiveGraphPolicy.admit(
            start, rootId, proposal(paths = setOf("src/main/Other.kt")),
            limits, budget, now)))
        assertEquals("CHILD_AUTHORITY_ESCALATION", deny(RecursiveGraphPolicy.admit(
            start, rootId, proposal(paths = setOf("specs/Frozen.md")),
            limits, budget, now)))
        assertEquals("CHILD_AUTHORITY_ESCALATION", deny(RecursiveGraphPolicy.admit(
            start, rootId, proposal(reads = setOf("search")), limits, budget, now)))
        val accepted = assertIs<RecursiveDecision.Allowed>(RecursiveGraphPolicy.admit(
            start, rootId, proposal(paths = setOf("src/main/Child.kt"),
                reads = setOf("read")), limits, budget, now))
        assertEquals(setOf("specs/Frozen.md"), accepted.child.frozenPaths)
        assertEquals(setOf("src/main/Child.kt"), accepted.child.writablePaths)
        val reviewer = RecursiveSnapshot(listOf(root(StageKind.REVIEW)))
        assertEquals("CHILD_AUTHORITY_ESCALATION", deny(RecursiveGraphPolicy.admit(
            reviewer, rootId, proposal(origin = StageKind.REVIEW,
                paths = setOf("specs/Child.md")), limits, budget, now)))
        assertIs<RecursiveDecision.Allowed>(RecursiveGraphPolicy.admit(reviewer, rootId,
            proposal(origin = StageKind.REVIEW), limits, budget, now))
    }
}

package ai.yuriilab.recursivecoder

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.fail

/** Offline fake executors: no Koog, tool privileges, physical writes or network. */
@Tag("integration")
class RecursiveGraphSchedulerIntegrationTest {
    private val now = Instant.parse("2026-11-12T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private class MutableClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
    }

    private fun writable(kind: StageKind): Set<String> = when (kind) {
        StageKind.SDD -> setOf("specs/Nested.md")
        StageKind.ATDD -> setOf("tests/acceptance/Nested.feature")
        StageKind.TDD -> setOf("tests/unit/NestedTest.kt")
        StageKind.DEVELOP -> setOf("src/main/Nested.kt")
        StageKind.REVIEW -> emptySet()
    }

    private fun scheduler(
        kind: StageKind, events: MutableList<RecursiveEvent>,
        stageClock: Clock = clock,
        maxTotalChildren: Int = 4,
        stageDurationMillis: Long = 3_000,
        globalDeadline: Instant = now.plusSeconds(10)
    ): RecursiveGraphScheduler {
        val graphId = GraphId("root-" + kind.name.lowercase())
        val stageRunId = StageRunId("root-stage-" + kind.name.lowercase())
        val graph = StageGraph(
            GraphCorrelation("session-78", graphId, "correlation-78"),
            StageGraphState.RUNNING,
            listOf(StageNode(StageCorrelation("session-78", graphId,
                stageRunId, "request-78", "correlation-78"),
                kind, StageState.RUNNING, AgentId("agent-" + kind.name.lowercase()))),
            emptyList(), StageBudget(100, 10_000, 3, 3)
        )
        return RecursiveGraphScheduler(
            RecursiveRootGrant(graph, stageRunId, "root-task", writable(kind),
                setOf("specs/Frozen.md"), setOf("read")),
            RecursiveLimits(10, maxTotalChildren, stageDurationMillis, globalDeadline),
            stageClock, RecursiveEventSink { events.add(it) }
        )
    }

    private fun request(
        ctx: RecursiveStageContext, id: String, task: String,
        tokens: Long = 20, tools: Long = 2, duration: Long = 2_000,
        depends: Set<GraphId> = emptySet(), scope: Set<String> = ctx.writablePaths
    ): RecursiveChildProposal = RecursiveChildProposal(
        requestId = id, parentGraphId = ctx.graphId,
        parentStageRunId = ctx.stageRunId, origin = ctx.origin,
        taskId = task, dependsOn = depends, writablePaths = scope,
        allowedReadTools = ctx.allowedReadTools, maxTokens = tokens,
        maxToolCalls = tools, maxDurationMillis = duration
    )

    @Test fun sddAndDeveloperBothRunNestedChildrenWithSharedBudgets() = runBlocking {
        for (kind in listOf(StageKind.SDD, StageKind.DEVELOP)) {
            val events = mutableListOf<RecursiveEvent>()
            val scheduler = scheduler(kind, events)
            var grandchild: GraphId? = null
            val outcome = scheduler.execute(scheduler.rootGraphId, RecursiveStageExecutor { root ->
                val first = assertIs<RecursiveDecision.Allowed>(
                    root.requestChild(request(root, "req-one", "child-one", tokens = 30)))
                assertEquals(kind, first.child.origin)
                val childResult = scheduler.execute(first.child.graphId, RecursiveStageExecutor { child ->
                    assertIs<RecursiveDecision.Allowed>(child.charge(RecursiveUsage(3, 1)))
                    val nested = assertIs<RecursiveDecision.Allowed>(
                        child.requestChild(request(child, "req-nested", "grandchild",
                            tokens = 5, tools = 1, duration = 500)))
                    grandchild = nested.child.graphId
                    assertEquals(RecursiveExecutionOutcome.Completed,
                        scheduler.execute(nested.child.graphId, RecursiveStageExecutor { leaf ->
                            assertEquals(kind, leaf.origin)
                            assertEquals(root.writablePaths, leaf.writablePaths)
                            assertEquals(root.frozenPaths, leaf.frozenPaths)
                            assertIs<RecursiveDecision.Allowed>(
                                leaf.charge(RecursiveUsage(2, 1)))
                            RecursiveExecutionOutcome.Completed
                        }))
                    RecursiveExecutionOutcome.Completed
                })
                assertEquals(RecursiveExecutionOutcome.Completed, childResult)
                assertIs<RecursiveDecision.Allowed>(root.charge(RecursiveUsage(5, 1)))
                RecursiveExecutionOutcome.Completed
            })
            assertEquals(RecursiveExecutionOutcome.Completed, outcome)
            val current = scheduler.current()
            assertEquals(3, current.nodes.size)
            assertTrue(current.nodes.all { it.state == RecursiveNodeState.SUCCEEDED })
            assertEquals(RecursiveUsage(30, 2), current.node(scheduler.rootGraphId)!!.delegated)
            assertEquals(RecursiveUsage(5, 1), current.nodes.first {
                it.parentGraphId == scheduler.rootGraphId }.delegated)
            assertEquals(2, events.count { it.kind == RecursiveEventKind.CHILD_RESERVED })
            assertTrue(events.any { it.graphId == grandchild &&
                it.parentGraphId != null && it.parentStageRunId != null &&
                it.kind == RecursiveEventKind.USAGE_CHARGED })
            assertEquals(3, events.count { it.kind == RecursiveEventKind.SUCCEEDED })
        }
    }

    @Test fun allFiveStageOriginsProduceTypedDenials() = runBlocking {
        for (kind in StageKind.entries) {
            val events = mutableListOf<RecursiveEvent>()
            val scheduler = scheduler(kind, events)
            val outcome = scheduler.execute(scheduler.rootGraphId, RecursiveStageExecutor { root ->
                val illegal = request(root, "req-illegal", "out-of-scope",
                    scope = setOf("src/main/Unowned.kt"))
                val denied = assertIs<RecursiveDecision.Denied>(root.requestChild(illegal))
                assertEquals("CHILD_AUTHORITY_ESCALATION", denied.code)
                val repeated = assertIs<RecursiveDecision.Denied>(root.requestChild(illegal))
                assertEquals("REPEATED_REQUEST", repeated.code)
                // Review can request read-only child work; it never gets write authority.
                if (kind == StageKind.REVIEW) {
                    val accepted = assertIs<RecursiveDecision.Allowed>(
                        root.requestChild(request(root, "req-review", "read-only-child",
                            scope = emptySet())))
                    assertEquals(emptySet(), accepted.child.writablePaths)
                    assertEquals(RecursiveExecutionOutcome.Completed,
                        scheduler.execute(accepted.child.graphId,
                            RecursiveStageExecutor { RecursiveExecutionOutcome.Completed }))
                }
                RecursiveExecutionOutcome.Completed
            })
            assertEquals(RecursiveExecutionOutcome.Completed, outcome, kind.name)
            assertEquals(2, events.count { it.kind == RecursiveEventKind.DENIED }, kind.name)
            assertTrue(events.filter { it.kind == RecursiveEventKind.DENIED }.all {
                it.origin == kind && it.graphId == scheduler.rootGraphId &&
                    it.requestId == "req-illegal" && it.code != null
            })
        }
    }

    @Test fun dependencySchedulingAndCancellationPropagate() = runBlocking {
        val events = mutableListOf<RecursiveEvent>()
        val scheduler = scheduler(StageKind.TDD, events)
        val outcome = scheduler.execute(scheduler.rootGraphId, RecursiveStageExecutor { root ->
            val first = assertIs<RecursiveDecision.Allowed>(
                root.requestChild(request(root, "req-first", "child-first")))
            val second = assertIs<RecursiveDecision.Allowed>(
                root.requestChild(request(root, "req-second", "child-second",
                    depends = setOf(first.child.graphId))))
            assertEquals(RecursiveExecutionOutcome.Denied("DEPENDENCY_NOT_SUCCEEDED"),
                scheduler.execute(second.child.graphId,
                    RecursiveStageExecutor { RecursiveExecutionOutcome.Completed }))
            assertEquals(RecursiveExecutionOutcome.Completed,
                scheduler.execute(first.child.graphId,
                    RecursiveStageExecutor { RecursiveExecutionOutcome.Completed }))
            scheduler.cancel(scheduler.rootGraphId)
            assertEquals(RecursiveNodeState.CANCELLED,
                scheduler.current().node(second.child.graphId)!!.state)
            assertTrue(root.cancellation.signals().requested)
            RecursiveExecutionOutcome.Completed
        })
        assertEquals(RecursiveExecutionOutcome.Cancelled, outcome)
        assertEquals(RecursiveNodeState.CANCELLED,
            scheduler.current().node(scheduler.rootGraphId)!!.state)
        assertTrue(scheduler.current().nodes.none { it.state == RecursiveNodeState.RUNNING })
        assertTrue(events.any { it.kind == RecursiveEventKind.CANCELLED &&
            it.parentGraphId == scheduler.rootGraphId })
    }

    @Test fun perParentRequestLimitCannotStarveASibling() = runBlocking {
        val events = mutableListOf<RecursiveEvent>()
        val scheduler = scheduler(StageKind.DEVELOP, events)
        val outcome = scheduler.execute(scheduler.rootGraphId, RecursiveStageExecutor { root ->
            val first = assertIs<RecursiveDecision.Allowed>(
                root.requestChild(request(root, "req-one", "child-one")))
            val sibling = assertIs<RecursiveDecision.Allowed>(
                root.requestChild(request(root, "req-two", "child-two")))
            assertEquals(RecursiveExecutionOutcome.Completed,
                scheduler.execute(first.child.graphId, RecursiveStageExecutor { firstCtx ->
                    repeat(16) {
                        assertEquals("INVALID_CHILD_IDENTITY",
                            assertIs<RecursiveDecision.Denied>(
                                firstCtx.requestChild(request(
                                    firstCtx, "invalid id", "invalid-work"))).code)
                    }
                    assertEquals("REQUEST_ATTEMPT_LIMIT",
                        assertIs<RecursiveDecision.Denied>(
                            firstCtx.requestChild(request(
                                firstCtx, "req-after-limit", "allowed-work"))).code)
                    RecursiveExecutionOutcome.Completed
                }))
            assertEquals(RecursiveExecutionOutcome.Completed,
                scheduler.execute(sibling.child.graphId, RecursiveStageExecutor { siblingCtx ->
                    val nested = assertIs<RecursiveDecision.Allowed>(
                        siblingCtx.requestChild(request(siblingCtx,
                            "req-sibling", "sibling-grandchild", tokens = 5, tools = 1)))
                    assertEquals(RecursiveExecutionOutcome.Completed,
                        scheduler.execute(nested.child.graphId,
                            RecursiveStageExecutor { RecursiveExecutionOutcome.Completed }))
                    RecursiveExecutionOutcome.Completed
                }))
            RecursiveExecutionOutcome.Completed
        })
        assertEquals(RecursiveExecutionOutcome.Completed, outcome)
        assertEquals(4, scheduler.current().nodes.size)
        assertTrue(events.any { it.kind == RecursiveEventKind.DENIED &&
            it.code == "REQUEST_ATTEMPT_LIMIT" })
    }

    @Test fun globalChildLimitAndFailedDependenciesStayFailClosed() = runBlocking {
        val events = mutableListOf<RecursiveEvent>()
        val maxOne = scheduler(StageKind.SDD, events, maxTotalChildren = 1)
        val limited = maxOne.execute(maxOne.rootGraphId, RecursiveStageExecutor { root ->
            val child = assertIs<RecursiveDecision.Allowed>(
                root.requestChild(request(root, "req-only", "only-child")))
            assertEquals("GLOBAL_CHILD_LIMIT",
                assertIs<RecursiveDecision.Denied>(
                    root.requestChild(request(root, "req-over", "second-child"))).code)
            assertEquals(RecursiveExecutionOutcome.Completed,
                maxOne.execute(child.child.graphId,
                    RecursiveStageExecutor { RecursiveExecutionOutcome.Completed }))
            RecursiveExecutionOutcome.Completed
        })
        assertEquals(RecursiveExecutionOutcome.Completed, limited)

        val other = scheduler(StageKind.TDD, mutableListOf())
        val failed = other.execute(other.rootGraphId, RecursiveStageExecutor { root ->
            val first = assertIs<RecursiveDecision.Allowed>(
                root.requestChild(request(root, "req-first", "first-child")))
            val dependent = assertIs<RecursiveDecision.Allowed>(
                root.requestChild(request(root, "req-dependent", "dependent",
                    depends = setOf(first.child.graphId))))
            assertEquals(RecursiveExecutionOutcome.Failed("FAKE_FAILURE"),
                other.execute(first.child.graphId,
                    RecursiveStageExecutor { RecursiveExecutionOutcome.Failed("FAKE_FAILURE") }))
            assertEquals(RecursiveExecutionOutcome.Denied("DEPENDENCY_NOT_SUCCEEDED"),
                other.execute(dependent.child.graphId,
                    RecursiveStageExecutor { fail("Blocked dependent was executed") }))
            RecursiveExecutionOutcome.Completed
        })
        assertEquals(RecursiveExecutionOutcome.Failed("CHILD_WORK_INCOMPLETE"), failed)
        assertTrue(other.current().nodes.none { it.state == RecursiveNodeState.READY })
    }

    @Test fun unexpectedExecutorCancellationFailsWithoutCancellingCaller() = runBlocking {
        val events = mutableListOf<RecursiveEvent>()
        val scheduler = scheduler(StageKind.TDD, events)
        val outcome = scheduler.execute(scheduler.rootGraphId,
            RecursiveStageExecutor {
                // No scheduler cancellation was requested; an executor can still
                // throw a CancellationException from its own nested operation.
                throw CancellationException("executor-local cancellation")
            })
        assertEquals(RecursiveExecutionOutcome.Failed("STAGE_EXECUTOR_FAILED"), outcome)
        assertEquals(RecursiveNodeState.FAILED,
            scheduler.current().node(scheduler.rootGraphId)!!.state)
        assertTrue(events.any { it.kind == RecursiveEventKind.FAILED &&
            it.code == "STAGE_EXECUTOR_FAILED" })
    }

    @Test fun callerCancellationRethrowsAfterTerminalStateRecorded() = runBlocking {
        val events = mutableListOf<RecursiveEvent>()
        val scheduler = scheduler(StageKind.DEVELOP, events)
        val started = CompletableDeferred<Unit>()
        var escaped = false
        val caller = async {
            try {
                scheduler.execute(scheduler.rootGraphId, RecursiveStageExecutor {
                    started.complete(Unit)
                    awaitCancellation()
                })
                fail("Cancelled caller returned normally")
            } catch (e: CancellationException) {
                escaped = true
                throw e
            }
        }
        started.await()
        caller.cancel()
        assertFailsWith<CancellationException> { caller.await() }
        assertTrue(escaped)
        assertEquals(RecursiveNodeState.CANCELLED,
            scheduler.current().node(scheduler.rootGraphId)!!.state)
        assertTrue(events.any { it.kind == RecursiveEventKind.CANCELLED })
    }

    @Test fun ownTimeoutAndInjectedClockDeadlinesFailClosed() = runBlocking {
        val events = mutableListOf<RecursiveEvent>()
        val short = scheduler(StageKind.SDD, events, stageDurationMillis = 40)
        lateinit var signal: Cancellation
        val timed = short.execute(short.rootGraphId, RecursiveStageExecutor { ctx ->
            signal = ctx.cancellation
            delay(2_000)
            RecursiveExecutionOutcome.Completed
        })
        assertEquals(RecursiveExecutionOutcome.TimedOut, timed)
        assertTrue(signal.signals().requested)
        assertEquals(RecursiveNodeState.TIMED_OUT,
            short.current().node(short.rootGraphId)!!.state)

        for ((advance, expected) in listOf(
            now.plusMillis(3_001) to "STAGE_DEADLINE",
            now.plusSeconds(11) to "GLOBAL_DEADLINE"
        )) {
            val manual = MutableClock(now)
            val scheduler = scheduler(StageKind.ATDD, mutableListOf(), stageClock = manual)
            val result = scheduler.execute(scheduler.rootGraphId, RecursiveStageExecutor { ctx ->
                manual.current = advance
                assertEquals(expected, assertIs<RecursiveDecision.Denied>(
                    ctx.charge(RecursiveUsage(1, 0))).code)
                assertTrue(ctx.cancellation.signals().requested)
                RecursiveExecutionOutcome.Completed
            })
            assertEquals(RecursiveExecutionOutcome.Failed("USAGE_CHARGE_DENIED"), result)
        }
    }

    @Test fun cancellingNestedChildDoesNotCancelItsParentExecutor() = runBlocking {
        val events = mutableListOf<RecursiveEvent>()
        val scheduler = scheduler(StageKind.DEVELOP, events)
        var parentCleanupRan = false
        val parentOutcome = scheduler.execute(scheduler.rootGraphId,
            RecursiveStageExecutor { parent ->
                val child = assertIs<RecursiveDecision.Allowed>(
                    parent.requestChild(request(parent, "req-child", "child-task")))
                coroutineScope {
                    val started = CompletableDeferred<Unit>()
                    val childResult = async {
                        scheduler.execute(child.child.graphId, RecursiveStageExecutor {
                            started.complete(Unit)
                            awaitCancellation()
                        })
                    }
                    started.await()
                    assertTrue(scheduler.cancel(child.child.graphId))
                    assertEquals(RecursiveExecutionOutcome.Cancelled, childResult.await())
                }
                // Cancellation must stay within the child's subtree.
                assertTrue(!parent.cancellation.signals().requested)
                assertIs<RecursiveDecision.Allowed>(parent.charge(RecursiveUsage(1, 0)))
                parentCleanupRan = true
                RecursiveExecutionOutcome.Completed
            })
        assertTrue(parentCleanupRan)
        // Parent is still permitted to finish; its incomplete child prevents success,
        // rather than incorrectly cascading a cancellation into the parent.
        assertEquals(RecursiveExecutionOutcome.Failed("CHILD_WORK_INCOMPLETE"), parentOutcome)
        val snapshot = scheduler.current()
        assertEquals(RecursiveNodeState.FAILED,
            snapshot.node(scheduler.rootGraphId)!!.state)
        assertTrue(snapshot.nodes.any { it.parentGraphId == scheduler.rootGraphId &&
            it.state == RecursiveNodeState.CANCELLED })
        assertTrue(events.any { it.kind == RecursiveEventKind.CANCELLED &&
            it.parentGraphId == scheduler.rootGraphId })
    }

    @Test fun deniedUsageChargeCannotBeIgnoredByExecutor() = runBlocking {
        val events = mutableListOf<RecursiveEvent>()
        val scheduler = scheduler(StageKind.ATDD, events)
        val outcome = scheduler.execute(scheduler.rootGraphId, RecursiveStageExecutor { root ->
            val child = assertIs<RecursiveDecision.Allowed>(
                root.requestChild(request(root, "req-expensive", "expensive-child",
                    tokens = 95, tools = 9)))
            assertEquals("TOKEN_BUDGET_EXHAUSTED",
                assertIs<RecursiveDecision.Denied>(
                    root.charge(RecursiveUsage(6, 0))).code)
            assertEquals(RecursiveNodeState.CANCELLED,
                scheduler.current().node(child.child.graphId)!!.state)
            RecursiveExecutionOutcome.Completed
        })
        assertEquals(RecursiveExecutionOutcome.Failed("USAGE_CHARGE_DENIED"), outcome)
        assertEquals(RecursiveUsage(95, 9),
            scheduler.current().node(scheduler.rootGraphId)!!.delegated)
        assertEquals(RecursiveUsage(0, 0),
            scheduler.current().node(scheduler.rootGraphId)!!.spent)
        assertTrue(events.any { it.kind == RecursiveEventKind.DENIED &&
            it.code == "TOKEN_BUDGET_EXHAUSTED" })
    }
}

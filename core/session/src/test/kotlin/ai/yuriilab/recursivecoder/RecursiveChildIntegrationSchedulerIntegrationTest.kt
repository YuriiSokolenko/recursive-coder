package ai.yuriilab.recursivecoder

import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Offline trusted-host and fake-stage fixtures: no physical write authority. */
@Tag("integration")
class RecursiveChildIntegrationSchedulerIntegrationTest {
    private val now = Instant.parse("2026-11-12T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun paths(kind: StageKind) = when (kind) {
        StageKind.SDD -> listOf("specs/Root.md", "specs/Parent.md", "specs/Leaf.md")
        StageKind.ATDD -> listOf("tests/acceptance/Root.feature",
            "tests/acceptance/Parent.feature", "tests/acceptance/Leaf.feature")
        StageKind.TDD -> listOf("tests/unit/RootTest.kt",
            "tests/unit/ParentTest.kt", "tests/unit/LeafTest.kt")
        StageKind.DEVELOP -> listOf("src/main/Root.kt",
            "src/main/Parent.kt", "src/main/Leaf.kt")
        StageKind.REVIEW -> emptyList()
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private inner class FakeTrustedHost : RecursiveChildIntegrationHost {
        val approved = mutableSetOf<ArtifactId>()
        val byChild = mutableMapOf<GraphId, List<StageArtifactManifest>>()
        val bytes = mutableMapOf<String, ByteArray>()
        override fun acceptedArtifactIds() = approved.toSet()
        override fun manifestsFor(child: RecursiveNode) = byChild[child.graphId]
        override fun readBytes(path: String) = bytes[path]?.clone()

        fun accept(child: RecursiveNode, path: String) {
            val data = ("verified:" + child.graphId.value).toByteArray()
            bytes[path] = data
            val manifest = StageArtifactManifest(
                ArtifactId("artifact-" + child.stageRunId.value),
                requireNotNull(child.origin.output), path, digest(data),
                child.origin.role, child.stageRunId, 1,
                ArtifactApproval.ACCEPTED, ArtifactFreeze.FROZEN)
            byChild[child.graphId] = listOf(manifest)
            approved += manifest.id
        }
    }

    private fun scheduler(kind: StageKind, host: FakeTrustedHost,
        events: MutableList<RecursiveEvent>): RecursiveGraphScheduler {
        val id = GraphId("root-" + kind.name.lowercase())
        val stage = StageRunId("stage-root-" + kind.name.lowercase())
        val graph = StageGraph(
            GraphCorrelation("session-79", id, "correlation-79"),
            StageGraphState.RUNNING,
            listOf(StageNode(StageCorrelation("session-79", id, stage,
                "request-79", "correlation-79"),
                kind, StageState.RUNNING, AgentId("agent-" + kind.name.lowercase()))),
            emptyList(), StageBudget(200, 60_000, 3, 4)
        )
        return RecursiveGraphScheduler(
            RecursiveRootGrant(graph, stage, "root-task", paths(kind).toSet(),
                setOf("specs/Frozen.md")), RecursiveLimits(12, 8, 12_000,
                now.plusSeconds(60)), clock, RecursiveEventSink { events += it }, host)
    }

    private fun proposal(ctx: RecursiveStageContext, requestId: String,
        taskId: String, paths: Set<String>, dependsOn: Set<GraphId> = emptySet(),
        tokens: Long = 40): RecursiveChildProposal = RecursiveChildProposal(
        requestId, ctx.graphId, ctx.stageRunId, ctx.origin, taskId,
        dependsOn, paths, emptySet(), tokens, 2, 1_000
    )

    @Test fun sddAndDeveloperIntegrateTwoLevelResultsWithOrderedCorrelatedEvents() = runBlocking {
        for (kind in listOf(StageKind.SDD, StageKind.DEVELOP)) {
            val host = FakeTrustedHost()
            val events = mutableListOf<RecursiveEvent>()
            val scheduler = scheduler(kind, host, events)
            val paths = paths(kind)
            val outcome = scheduler.execute(scheduler.rootGraphId, RecursiveStageExecutor { root ->
                val parent = assertIs<RecursiveDecision.Allowed>(
                    root.requestChild(proposal(root, "request-parent", "parent-task",
                        setOf(paths[1], paths[2]), tokens = 80))).child
                assertEquals(RecursiveExecutionOutcome.Completed, scheduler.execute(
                    parent.graphId, RecursiveStageExecutor { child ->
                        val leaf = assertIs<RecursiveDecision.Allowed>(
                            child.requestChild(proposal(child, "request-leaf", "leaf-task",
                                setOf(paths[2])))).child
                        assertEquals(RecursiveExecutionOutcome.Completed, scheduler.execute(
                            leaf.graphId, RecursiveStageExecutor {
                                host.accept(leaf, paths[2])
                                RecursiveExecutionOutcome.Completed
                            }))
                        assertEquals(listOf(leaf.graphId),
                            assertIs<ChildIntegrationDecision.Accepted>(
                                child.integrateChildren()).results.childOrder)
                        host.accept(parent, paths[1])
                        RecursiveExecutionOutcome.Completed
                    }))
                val accepted = assertIs<ChildIntegrationDecision.Accepted>(
                    root.integrateChildren()).results
                assertEquals(listOf(parent.graphId), accepted.childOrder)
                assertEquals(listOf(paths[2], paths[1]),
                    accepted.artifacts.map { it.manifest.declaredPath })
                RecursiveExecutionOutcome.Completed
            })
            assertEquals(RecursiveExecutionOutcome.Completed, outcome)
            assertTrue(scheduler.current().nodes.all {
                it.state == RecursiveNodeState.SUCCEEDED
            })
            assertEquals(2, scheduler.integratedResults(scheduler.rootGraphId)
                ?.artifacts?.size)
            assertEquals(2, events.count { it.kind == RecursiveEventKind.INTEGRATED })
            assertTrue(events.filter { it.kind == RecursiveEventKind.INTEGRATED }.all {
                it.childGraphId != null && it.requestId != null && it.origin == kind
            })
        }
    }

    @Test fun explicitIntegrationIsRequiredAndRecheckedAtCommit() = runBlocking {
        val host = FakeTrustedHost()
        val firstScheduler = scheduler(StageKind.SDD, host, mutableListOf())
        val without = firstScheduler.execute(firstScheduler.rootGraphId, RecursiveStageExecutor { root ->
            val node = assertIs<RecursiveDecision.Allowed>(
                root.requestChild(proposal(root, "request-child", "child-task",
                    setOf("specs/Parent.md")))).child
            assertEquals(RecursiveExecutionOutcome.Completed, firstScheduler.execute(node.graphId,
                RecursiveStageExecutor {
                    host.accept(node, "specs/Parent.md")
                    RecursiveExecutionOutcome.Completed
                }))
            RecursiveExecutionOutcome.Completed
        })
        assertEquals(RecursiveExecutionOutcome.Failed("CHILD_RESULTS_NOT_INTEGRATED"), without)

        val secondHost = FakeTrustedHost()
        val second = scheduler(StageKind.DEVELOP, secondHost, mutableListOf())
        val stale = second.execute(second.rootGraphId, RecursiveStageExecutor { root ->
            val node = assertIs<RecursiveDecision.Allowed>(
                root.requestChild(proposal(root, "request-child", "child-task",
                    setOf("src/main/Parent.kt")))).child
            assertEquals(RecursiveExecutionOutcome.Completed, second.execute(node.graphId,
                RecursiveStageExecutor {
                    secondHost.accept(node, "src/main/Parent.kt")
                    RecursiveExecutionOutcome.Completed
                }))
            assertIs<ChildIntegrationDecision.Accepted>(root.integrateChildren())
            secondHost.bytes["src/main/Parent.kt"] = "tampered".toByteArray()
            RecursiveExecutionOutcome.Completed
        })
        assertEquals(RecursiveExecutionOutcome.Failed("CHILD_INTEGRATION_STALE"), stale)
    }

    @Test fun missingOrUnacceptedChildEvidenceFailsClosed() = runBlocking {
        val host = FakeTrustedHost()
        val events = mutableListOf<RecursiveEvent>()
        val scheduler = scheduler(StageKind.TDD, host, events)
        val outcome = scheduler.execute(scheduler.rootGraphId, RecursiveStageExecutor { root ->
            val node = assertIs<RecursiveDecision.Allowed>(
                root.requestChild(proposal(root, "request-child", "child-task",
                    setOf("tests/unit/ParentTest.kt")))).child
            assertEquals(RecursiveExecutionOutcome.Completed, scheduler.execute(node.graphId,
                RecursiveStageExecutor {
                    host.accept(node, "tests/unit/ParentTest.kt")
                    host.approved.clear() // Simulates a revoked host approval.
                    RecursiveExecutionOutcome.Completed
                }))
            assertEquals("CHILD_ARTIFACT_UNACCEPTED",
                assertIs<ChildIntegrationDecision.Denied>(root.integrateChildren()).code)
            RecursiveExecutionOutcome.Completed
        })
        assertEquals(RecursiveExecutionOutcome.Failed("CHILD_RESULTS_NOT_INTEGRATED"), outcome)
        assertTrue(events.any { it.kind == RecursiveEventKind.DENIED &&
            it.code == "CHILD_ARTIFACT_UNACCEPTED" })
    }

    @Test fun reviewerChildRemainsReadOnlyAndMustBeExplicitlyIntegrated() = runBlocking {
        val host = FakeTrustedHost()
        val scheduler = scheduler(StageKind.REVIEW, host, mutableListOf())
        val result = scheduler.execute(scheduler.rootGraphId, RecursiveStageExecutor { root ->
            val node = assertIs<RecursiveDecision.Allowed>(
                root.requestChild(proposal(root, "request-review", "review-child",
                    emptySet()))).child
            assertTrue(node.writablePaths.isEmpty())
            assertEquals(RecursiveExecutionOutcome.Completed, scheduler.execute(node.graphId,
                RecursiveStageExecutor {
                    host.byChild[node.graphId] = emptyList()
                    RecursiveExecutionOutcome.Completed
                }))
            assertIs<ChildIntegrationDecision.Accepted>(root.integrateChildren())
            RecursiveExecutionOutcome.Completed
        })
        assertEquals(RecursiveExecutionOutcome.Completed, result)
    }
}

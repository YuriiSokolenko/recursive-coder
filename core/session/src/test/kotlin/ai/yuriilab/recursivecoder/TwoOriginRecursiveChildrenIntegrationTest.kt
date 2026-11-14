package ai.yuriilab.recursivecoder

import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Independent trusted fake receipt sources, with a shared Session v1 journal. */
@Tag("integration")
class TwoOriginRecursiveChildrenIntegrationTest {
    private val now = Instant.parse("2026-11-14T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private class MemorySink : EventSink {
        val records = mutableListOf<JsonObject>()
        override fun append(event: JsonObject) { records += event }
        override fun flush() = Unit
        override fun sha256(): String = "a".repeat(64)
        override fun close() = Unit
    }

    private class TrustedHost : RecursiveChildIntegrationHost {
        val accepted = mutableSetOf<ArtifactId>()
        val manifests = mutableMapOf<GraphId, List<StageArtifactManifest>>()
        val bytes = mutableMapOf<String, ByteArray>()
        override fun acceptedArtifactIds() = accepted.toSet()
        override fun manifestsFor(child: RecursiveNode) = manifests[child.graphId]
        override fun readBytes(path: String) = bytes[path]?.clone()

        fun accept(child: RecursiveNode, path: String, id: ArtifactId =
            ArtifactId("artifact-" + child.stageRunId.value)) {
            val content = ("trusted:" + child.graphId.value).toByteArray()
            bytes[path] = content
            val hash = MessageDigest.getInstance("SHA-256").digest(content)
                .joinToString("") { "%02x".format(it) }
            val manifest = StageArtifactManifest(id, requireNotNull(child.origin.output),
                path, hash, child.origin.role, child.stageRunId, 1,
                ArtifactApproval.ACCEPTED, ArtifactFreeze.FROZEN)
            accepted += id
            manifests[child.graphId] = listOf(manifest)
        }
    }

    private fun paths(kind: StageKind): Pair<String, String> = when (kind) {
        StageKind.SDD -> "specs/Child.md" to "specs/Leaf.md"
        StageKind.DEVELOP -> "src/main/Child.kt" to "src/main/Leaf.kt"
        else -> error("Unsupported fixture origin")
    }

    private fun source(kind: StageKind, host: TrustedHost,
        acceptedId: ArtifactId? = null,
        acceptChildren: Boolean = true,
        onExecute: () -> Unit = {}): RecursiveOriginSource {
        val identity = kind.name.lowercase()
        val graphId = GraphId("root-" + identity)
        val stageId = StageRunId("root-stage-" + identity)
        val (parentPath, leafPath) = paths(kind)
        val graph = StageGraph(
            GraphCorrelation("session-79", graphId, "correlation-" + identity),
            StageGraphState.RUNNING,
            listOf(StageNode(StageCorrelation("session-79", graphId, stageId,
                "request-" + identity, "correlation-" + identity),
                kind, StageState.RUNNING, AgentId("actor-" + identity))),
            emptyList(), StageBudget(500, 90_000, 3, 4)
        )
        val root = RecursiveRootGrant(graph, stageId, "task-root-" + identity,
            setOf(parentPath, leafPath), emptySet())
        val executor = RecursiveStageExecutor { context ->
            onExecute()
            val parent = assertIs<RecursiveDecision.Allowed>(context.requestChild(
                RecursiveChildProposal("parent-" + identity, context.graphId,
                    context.stageRunId, context.origin, "task-parent-" + identity,
                    writablePaths = setOf(parentPath, leafPath),
                    maxTokens = 200, maxToolCalls = 4, maxDurationMillis = 1_000)
            )).child
            val parentOutcome = context.executeChild(
                parent.graphId, RecursiveStageExecutor { intermediate ->
                    val leaf = assertIs<RecursiveDecision.Allowed>(intermediate.requestChild(
                        RecursiveChildProposal("leaf-" + identity, intermediate.graphId,
                            intermediate.stageRunId, intermediate.origin,
                            "task-leaf-" + identity,
                            writablePaths = setOf(leafPath),
                            maxTokens = 60, maxToolCalls = 1,
                            maxDurationMillis = 1_000)
                    )).child
                    assertEquals(RecursiveExecutionOutcome.Completed,
                        intermediate.executeChild(leaf.graphId, RecursiveStageExecutor {
                            if (acceptChildren) host.accept(leaf, leafPath,
                                acceptedId ?: ArtifactId("artifact-" + leaf.stageRunId.value))
                            RecursiveExecutionOutcome.Completed
                        }))
                    if (acceptChildren) {
                        assertIs<ChildIntegrationDecision.Accepted>(
                            intermediate.integrateChildren())
                        host.accept(parent, parentPath)
                    }
                    RecursiveExecutionOutcome.Completed
                })
            if (!acceptChildren) {
                return@RecursiveStageExecutor RecursiveExecutionOutcome.Failed(
                    "CHILD_NOT_APPROVED")
            }
            assertEquals(RecursiveExecutionOutcome.Completed, parentOutcome)
            assertIs<ChildIntegrationDecision.Accepted>(context.integrateChildren())
            RecursiveExecutionOutcome.Completed
        }
        return RecursiveOriginSource(root,
            RecursiveLimits(12, 8, 20_000, now.plusSeconds(90)), host, executor)
    }

    @Test fun sddAndDeveloperNestedOriginsIntegrateWithOneCorrelatedEventChain() = runBlocking {
        val sink = MemorySink()
        val journal = SessionEventJournal("session-79", "trace-79", "request-79",
            clock, sink)
        val events = CorrelatedRecursiveSessionEvents(journal)
        val result = RunTwoOriginRecursiveChildrenUseCase(clock, events).execute(
            source(StageKind.DEVELOP, TrustedHost()),
            source(StageKind.SDD, TrustedHost()))
        val completed = assertIs<TwoOriginRecursiveOutcome.Completed>(result)
        assertEquals(listOf(StageKind.SDD, StageKind.DEVELOP),
            completed.integrated.keys.toList())
        for ((kind, receipt) in completed.integrated) {
            assertEquals(2, receipt.artifacts.size)
            assertEquals(1, receipt.childOrder.size)
            assertTrue(completed.snapshots.getValue(kind).nodes.all {
                it.state == RecursiveNodeState.SUCCEEDED
            })
        }
        assertEquals(2, sink.records.count {
            it["metadata"]!!.jsonObject["recursiveEvent"]?.jsonPrimitive?.content ==
                RecursiveEventKind.INTEGRATED.name &&
                it["metadata"]!!.jsonObject["graphId"]?.jsonPrimitive?.content
                    ?.startsWith("root-") == true
        })
        assertTrue(sink.records.any {
            it["metadata"]!!.jsonObject["origin"]?.jsonPrimitive?.content == "SDD"
        })
        assertTrue(sink.records.any {
            it["metadata"]!!.jsonObject["origin"]?.jsonPrimitive?.content == "DEVELOP"
        })
        assertEquals((1..sink.records.size).toList(), sink.records.map {
            it["sequence"]!!.jsonPrimitive.content.toInt()
        })
        assertEquals("a".repeat(64), journal.finish())
    }

    @Test fun unacceptedFirstOriginBlocksSecond() = runBlocking {
        var secondStarted = false
        val outcome = RunTwoOriginRecursiveChildrenUseCase(clock).execute(
            source(StageKind.SDD, TrustedHost(), acceptChildren = false),
            source(StageKind.DEVELOP, TrustedHost(), onExecute = { secondStarted = true }))
        val stopped = assertIs<TwoOriginRecursiveOutcome.Stopped>(outcome)
        assertEquals(StageKind.SDD, stopped.origin)
        assertTrue(stopped.code != "SUCCEEDED")
        assertTrue(!secondStarted)
    }

    @Test fun duplicateOriginIsDeniedBeforeExecution() = runBlocking {
        var invoked = false
        val outcome = RunTwoOriginRecursiveChildrenUseCase(clock).execute(
            source(StageKind.SDD, TrustedHost(), onExecute = { invoked = true }),
            source(StageKind.SDD, TrustedHost(), onExecute = { invoked = true }))
        assertEquals("INVALID_ORIGIN_PAIR",
            assertIs<TwoOriginRecursiveOutcome.Stopped>(outcome).code)
        assertTrue(!invoked)
    }

    @Test fun duplicateArtifactIdentityAcrossOriginsIsNotMerged() = runBlocking {
        val duplicate = ArtifactId("shared-artifact")
        val outcome = RunTwoOriginRecursiveChildrenUseCase(clock).execute(
            source(StageKind.SDD, TrustedHost(), acceptedId = duplicate),
            source(StageKind.DEVELOP, TrustedHost(), acceptedId = duplicate))
        val denied = assertIs<TwoOriginRecursiveOutcome.Stopped>(outcome)
        assertEquals(StageKind.DEVELOP, denied.origin)
        assertEquals("CROSS_ORIGIN_ARTIFACT_CONFLICT", denied.code)
    }

    @Test fun secondOriginCannotInvalidateFirstOriginsAcceptedBytes() = runBlocking {
        val firstHost = TrustedHost()
        val result = RunTwoOriginRecursiveChildrenUseCase(clock).execute(
            source(StageKind.SDD, firstHost),
            source(StageKind.DEVELOP, TrustedHost(), onExecute = {
                firstHost.bytes["specs/Leaf.md"] = "tampered".toByteArray()
            }))
        val denied = assertIs<TwoOriginRecursiveOutcome.Stopped>(result)
        assertEquals(StageKind.SDD, denied.origin)
        assertEquals("CHILD_EVIDENCE_STALE", denied.code)
    }

    @Test fun lostSessionEventsMakeTwoOriginSuccessImpossible() = runBlocking {
        val failure = RecursiveEventSink { _: RecursiveEvent -> error("journal write failure") }
        val result = RunTwoOriginRecursiveChildrenUseCase(clock, failure).execute(
            source(StageKind.SDD, TrustedHost()),
            source(StageKind.DEVELOP, TrustedHost()))
        val denied = assertIs<TwoOriginRecursiveOutcome.Stopped>(result)
        assertEquals(StageKind.SDD, denied.origin)
        assertEquals("EVENT_DELIVERY_FAILED", denied.code)
    }
}

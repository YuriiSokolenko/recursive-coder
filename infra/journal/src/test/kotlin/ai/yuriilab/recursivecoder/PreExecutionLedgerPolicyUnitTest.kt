package ai.yuriilab.recursivecoder

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Pure policy regressions. The physical locking, replay and publication
 * contracts are exercised separately by the integration tier.
 */
@Tag("unit")
class PreExecutionLedgerPolicyUnitTest {
    private val actor = buildJsonObject { put("agentId", "agent-one") }
    private val graph = buildJsonObject { put("graphId", "graph-root") }

    private fun event(
        type: String = "checkpoint.saved",
        status: String = "completed",
        actorValue: JsonObject = actor,
        metadata: JsonObject? = graph,
        transition: JsonObject? = null
    ): JsonObject = buildJsonObject {
        put("type", type)
        put("status", status)
        put("actor", actorValue)
        if (metadata != null) put("metadata", metadata)
        if (transition != null) put("transition", transition)
    }

    private fun edge(from: String = "created", to: String = "accepted") =
        buildJsonObject { put("from", from); put("to", to) }

    private fun snapshot() = SessionCheckpoint(
        sequence = 1,
        previousSha256 = null,
        binding = CheckpointBinding("a".repeat(64), "b".repeat(64),
            "c".repeat(40), "refs/heads/dev", "d".repeat(64),
            "graph-root", 1, "e".repeat(64)),
        sessionId = "sess-one",
        traceId = "trace-one",
        phase = SessionPhaseVocabulary.createdWire,
        taskId = "task-one",
        taskAttempt = 0,
        taskState = "created",
        children = listOf(CheckpointChild("child-one", "graph-root", "ready")),
        acceptedArtifacts = emptyList(),
        hostAssertedUsage = listOf(
            HostAssertedCheckpointUsage("task-one", "model", "known", 0L)),
        pendingEffects = emptyList(),
        journalSequence = 1,
        journalHeadSha256 = "f".repeat(64),
        journalFileSha256 = "f".repeat(64),
        lastTrustedJournalOffset = 128
    )


    @Test fun fencedGraphMetadataMustMatchAdmittedRoot() {
        val root = "graph-root"
        val permitted = listOf(
            event(metadata = graph),
            event(metadata = buildJsonObject {}),
            event(metadata = null)
        )
        for (item in permitted)
            assertTrue(FencedGraphMetadataPolicy.permittedFencedGraphMetadata(item, root))
        val denied = listOf(
            buildJsonObject { put("graphId", "other") },
            buildJsonObject { put("parentGraphId", root) },
            buildJsonObject { put("childGraphId", root) },
            buildJsonObject {
                put("graphId", root)
                put("childGraphId", "other")
            }
        )
        for (metadata in denied)
            assertFalse(FencedGraphMetadataPolicy.permittedFencedGraphMetadata(
                event(metadata = metadata), root))
    }

    @Test fun appendPolicyAcceptsOnlyCompletedMetadataAndExactLifecycleEdge() {
        val created = SessionPhaseVocabulary.createdWire
        val accepted = SessionPhaseVocabulary.acceptedWire
        assertEquals("created", created)
        assertEquals("accepted", accepted)

        data class Case(val name: String, val event: JsonObject, val phase: String,
                        val accepted: Boolean)
        val cases = listOf(
            Case("metadata in created", event(), created, true),
            Case("metadata in accepted", event(), accepted, true),
            Case("exact edge", event(type = "session.transition", transition = edge()),
                created, true),
            Case("unexpected type", event(type = "tool.started"), created, false),
            Case("non-completed status", event(status = "started"), created, false),
            Case("child actor", event(actorValue = buildJsonObject {
                put("agentId", "agent-one"); put("childAgentId", "child-one")
            }), created, false),
            Case("execution actor", event(actorValue = buildJsonObject {
                put("agentId", "agent-one"); put("toolExecutionId", "tool-one")
            }), created, false),
            Case("missing actor", event(actorValue = buildJsonObject {}),
                created, false),
            Case("stage metadata", event(type = "session.transition",
                transition = edge(), metadata = buildJsonObject {
                    put("graphId", "graph-root"); put("stageState", "ready")
                }), created, false),
            Case("missing explicit edge", event(type = "session.transition"),
                created, false),
            Case("wrong from", event(type = "session.transition",
                transition = edge("accepted", "accepted")), created, false),
            Case("running edge", event(type = "session.transition",
                transition = edge("accepted", "running")), accepted, false),
            Case("duplicate edge", event(type = "session.transition",
                transition = edge()), accepted, false),
            Case("metadata with transition", event(transition = edge()),
                created, false),
            Case("edge with extra fields", event(type = "session.transition",
                transition = buildJsonObject {
                    put("from", created); put("to", accepted); put("reason", "host")
                }), created, false)
        )
        for (case in cases) {
            assertEquals(case.accepted,
                (PreExecutionPhasePolicy.step(case.event, case.phase) != null),
                case.name)
            assertEquals(case.accepted,
                PreExecutionPhasePolicy.step(case.event, case.phase) != null,
                case.name + " / next phase")
        }
    }

    @Test fun stepCannotAdvanceWithoutValidTransitionAndCheckpointMetadataNeverChangesPhase() {
        val created = SessionPhaseVocabulary.createdWire
        val accepted = SessionPhaseVocabulary.acceptedWire
        val checkpointLikeMetadata = buildJsonObject {
            put("from", "accepted")
            put("to", "running")
            put("stageState", "ready")
        }
        val saved = event(metadata = checkpointLikeMetadata)
        assertNull(PreExecutionPhasePolicy.step(saved, created))
        assertNull(PreExecutionPhasePolicy.step(saved, accepted))
        assertEquals(created, PreExecutionPhasePolicy.step(event(), created))
        assertEquals(accepted, PreExecutionPhasePolicy.step(event(), accepted))
        assertEquals(accepted, PreExecutionPhasePolicy.step(
            event(type = "session.transition", transition = edge()), created))
        for (candidate in listOf(
            event(type = "session.transition", transition = edge("created", "running")),
            event(type = "session.transition", transition = edge("accepted", "running")),
            event(type = "session.transition"),
            event(transition = edge())
        )) {
            assertNull(PreExecutionPhasePolicy.step(candidate, created))
        }
        assertNull(PreExecutionPhasePolicy.step(
            event(type = "session.transition", transition = edge()), accepted))
    }

    @Test fun verifiedHistoryRequiresExactlyOneExplicitEdgeForAccepted() {
        val created = SessionPhaseVocabulary.createdWire
        val accepted = SessionPhaseVocabulary.acceptedWire
        val marker = event()
        val transition = event(type = "session.transition", transition = edge())
        assertEquals(created, PreExecutionPhasePolicy.phaseFromVerifiedHistory(
            created, listOf(marker)))
        assertEquals(accepted, PreExecutionPhasePolicy.phaseFromVerifiedHistory(
            accepted, listOf(marker, transition, marker)))
        assertNull(PreExecutionPhasePolicy.phaseFromVerifiedHistory(accepted, listOf(marker)),
            "accepted without a transition has no proof")
        assertNull(PreExecutionPhasePolicy.phaseFromVerifiedHistory(
            created, listOf(transition)), "created may not hide an edge")
        assertNull(PreExecutionPhasePolicy.phaseFromVerifiedHistory(
            accepted, listOf(transition, transition)), "repeated edges are forbidden")
        assertNull(PreExecutionPhasePolicy.phaseFromVerifiedHistory(
            accepted, listOf(event(type = "session.transition"))),
            "historical edge-less transitions fail closed")
        assertNull(PreExecutionPhasePolicy.phaseFromVerifiedHistory(
            "running", listOf(marker)))
    }

    @Test fun safeSnapshotRejectsPhysicalExecutionAndUncertainState() {
        val base = snapshot()
        assertTrue(PreExecutionSnapshotPolicy.safe(base))
        assertTrue(PreExecutionSnapshotPolicy.safe(base.copy(
            phase = SessionPhaseVocabulary.acceptedWire, taskState = "ready")))
        val unsafe = listOf(
            base.copy(phase = "running"),
            base.copy(taskState = "running"),
            base.copy(taskAttempt = 1),
            base.copy(children = listOf(CheckpointChild("child-one", "graph-root", "running"))),
            base.copy(acceptedArtifacts = listOf(
                CheckpointArtifact("artifact-one", "child-one", "f".repeat(64)))),
            base.copy(pendingEffects = listOf(CheckpointEffect("effect-one", "req", "unknown"))),
            base.copy(hostAssertedUsage = listOf(
                HostAssertedCheckpointUsage("task-one", "model", "known", 1L))),
            base.copy(hostAssertedUsage = listOf(
                HostAssertedCheckpointUsage("task-one", "model", "unknown", null)))
        )
        unsafe.forEachIndexed { index, candidate ->
            assertFalse(PreExecutionSnapshotPolicy.safe(candidate), "unsafe case $index")
        }
    }
}

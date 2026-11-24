package ai.yuriilab.recursivecoder

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/** No host I/O: running boundary cannot widen pre-execution or execution policy. */
@Tag("unit")
class TrustedRunningBoundaryPolicyUnitTest {
    private fun frame(sequence: Int, type: String, edge: Pair<String, String>? = null,
                      actor: SessionEventActor? = null,
                      metadata: JsonObject = buildJsonObject { put("graphId", "graph-root") }
    ) = SessionEventJournal.frame("sess-one", "trace-one", "req-one",
        sequence, Instant.parse("2026-11-20T12:00:00Z"), type, "completed",
        transition = edge, actor = actor, extra = metadata)

    @Test fun validFinalEdgeHasNoExecutionAuthorityOrResumeHistory() {
        val c1 = frame(1, "checkpoint.saved")
        val c2 = frame(2, "session.transition", "created" to "accepted")
        val c3 = frame(3, "session.transition", "accepted" to "running")
        assertEquals("running", TrustedRunningBoundaryPolicy.step(c3, "accepted"))
        assertEquals("running",
            TrustedRunningBoundaryPolicy.phaseFromVerifiedHistory("running", listOf(c1, c2, c3)))
        assertNull(PreExecutionPhasePolicy.step(c3, "accepted"))
        assertNull(PreExecutionPhasePolicy.phaseFromVerifiedHistory(
            "running", listOf(c1, c2, c3)))
        assertNull(TrustedRunningBoundaryPolicy.phaseFromVerifiedHistory(
            "accepted", listOf(c1, c2, c3)))
        assertNull(TrustedRunningBoundaryPolicy.phaseFromVerifiedHistory(
            "running", listOf(c1, c2, c3, frame(4, "checkpoint.saved"))))
    }

    @Test fun badEdgesAndExecutionShapedActorOrMetadataAreRejected() {
        val root = frame(3, "session.transition", "accepted" to "running")
        val child = frame(3, "session.transition", "accepted" to "running",
            actor = SessionEventActor("agent-sess-one", childAgentId = "agent-child"))
        val wrongActor = frame(3, "session.transition", "accepted" to "running",
            actor = SessionEventActor("agent-not-root"))
        val withReceipt = frame(3, "session.transition", "accepted" to "running",
            metadata = buildJsonObject {
                put("graphId", "graph-root")
                put("receiptId", "effect-one")
            })
        val unknownNullActorKey = JsonObject(root.toMutableMap().also {
            it["actor"] = JsonObject(root["actor"]!!.jsonObject.toMutableMap().also { fields ->
                fields["futureActorField"] = JsonNull
            })
        })
        val missingActorKey = JsonObject(root.toMutableMap().also {
            it["actor"] = JsonObject(root["actor"]!!.jsonObject.toMutableMap().also { fields ->
                fields.remove("childSessionId")
            })
        })
        val withTiming = JsonObject(root.toMutableMap().also {
            it["timing"] = buildJsonObject { put("startedAt", "2026-11-20T12:00:00Z") }
        })
        for (invalid in listOf(
            frame(3, "session.transition", "created" to "running"),
            frame(3, "session.transition", "accepted" to "accepted"),
            frame(3, "session.transition"),
            frame(3, "tool.started", "accepted" to "running"),
            child, wrongActor, withReceipt, withTiming,
            unknownNullActorKey, missingActorKey
        )) assertNull(TrustedRunningBoundaryPolicy.step(invalid, "accepted"))
        assertNull(TrustedRunningBoundaryPolicy.step(root, "created"))
        assertNull(TrustedRunningBoundaryPolicy.step(root, "running"))
    }

    @Test fun literalWireEventCannotTurnMetadataIntoExecutionAuthority() {
        // The oracle is independent of the production emitter and vocabulary.
        val event = Json.parseToJsonElement("""
            {
              "type": "session.transition", "status": "completed",
              "sessionId": "sess-one",
              "actor": {"agentId": "agent-sess-one", "parentAgentId": null,
                        "childAgentId": null, "toolExecutionId": null, "childSessionId": null},
              "metadata": {"graphId": "graph-root"},
              "transition": {"from": "accepted", "to": "running"}
            }
        """.trimIndent()).jsonObject
        assertEquals("running", TrustedRunningBoundaryPolicy.step(event, "accepted"))
        assertNull(PreExecutionPhasePolicy.step(event, "accepted"))
        assertNull(TrustedRunningBoundaryPolicy.step(
            JsonObject(event + ("status" to JsonPrimitive("started"))), "accepted"))
        assertNull(TrustedRunningBoundaryPolicy.step(
            JsonObject(event + ("metadata" to buildJsonObject {
                put("graphId", "graph-root")
                put("effectState", "completed")
            })), "accepted"))
        assertNull(TrustedRunningBoundaryPolicy.step(
            JsonObject(event + ("metadata" to buildJsonObject { put("graphId", 1) })),
            "accepted"))
    }

    @Test fun anyPreviousExecutionEventPoisonsRunningHistory() {
        val c1 = frame(1, "checkpoint.saved")
        val c2 = frame(2, "session.transition", "created" to "accepted")
        val tool = frame(3, "tool.started")
        val running = frame(4, "session.transition", "accepted" to "running")
        assertNull(TrustedRunningBoundaryPolicy.phaseFromVerifiedHistory(
            "running", listOf(c1, c2, tool, running)))
        assertNull(TrustedRunningBoundaryPolicy.phaseFromVerifiedHistory(
            "running", listOf(c1, running)))
    }
}

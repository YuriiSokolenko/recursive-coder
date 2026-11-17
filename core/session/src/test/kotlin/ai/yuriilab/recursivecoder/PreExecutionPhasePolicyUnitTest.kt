package ai.yuriilab.recursivecoder

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Pure history regression for the #113 admission compatibility hardening. */
@Tag("unit")
class PreExecutionPhasePolicyUnitTest {
    private fun event(
        type: String = "checkpoint.saved",
        status: String = "completed",
        metadata: JsonObject = buildJsonObject { put("graphId", "graph-root") },
        actor: JsonObject = buildJsonObject { put("agentId", "agent-one") },
        edge: JsonObject? = null
    ) = buildJsonObject {
        put("type", type)
        put("status", status)
        put("actor", actor)
        put("metadata", metadata)
        if (edge != null) put("transition", edge)
    }

    private fun transition(from: String = "created", to: String = "accepted") =
        buildJsonObject { put("from", from); put("to", to) }

    @Test fun explicitEdgeIsRequiredAndCannotBeRepeatedOrSkipped() {
        val edge = event("session.transition", edge = transition())
        assertEquals("accepted", PreExecutionPhasePolicy.step(edge, "created"))
        assertNull(PreExecutionPhasePolicy.step(edge, "accepted"))
        assertNull(PreExecutionPhasePolicy.step(
            event("session.transition"), "created"))
        assertNull(PreExecutionPhasePolicy.step(
            event("session.transition", edge = transition("created", "running")), "created"))
        assertNull(PreExecutionPhasePolicy.step(
            event("session.transition", edge = transition("accepted", "accepted")), "created"))
        assertNull(PreExecutionPhasePolicy.step(event("tool.started"), "created"))
        assertNull(PreExecutionPhasePolicy.step(event(status = "started"), "created"))
        assertNull(PreExecutionPhasePolicy.step(event(actor = buildJsonObject {
            put("agentId", "agent-one"); put("toolExecutionId", "tool-one")
        }), "created"))
        assertNull(PreExecutionPhasePolicy.step(event(
            "session.transition",
            metadata = buildJsonObject { put("graphId", "graph-root"); put("stageState", "ready") },
            edge = transition()), "created"))
    }

    @Test fun checkpointMetadataIsPhaseNeutralAndHistoryMustMatchSnapshot() {
        val saved = event()
        val edge = event("session.transition", edge = transition())
        assertEquals("created", PreExecutionPhasePolicy.phaseFromVerifiedHistory(
            "created", listOf(saved)))
        assertNull(PreExecutionPhasePolicy.phaseFromVerifiedHistory(
            "accepted", listOf(saved)))
        assertEquals("accepted", PreExecutionPhasePolicy.phaseFromVerifiedHistory(
            "accepted", listOf(saved, edge, saved)))
        assertNull(PreExecutionPhasePolicy.phaseFromVerifiedHistory(
            "created", listOf(saved, edge)))
        assertNull(PreExecutionPhasePolicy.phaseFromVerifiedHistory(
            "accepted", listOf(saved, edge, edge)))
    }
}

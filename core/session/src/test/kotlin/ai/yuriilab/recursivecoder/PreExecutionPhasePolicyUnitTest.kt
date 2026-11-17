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


    @Test fun checkpointSavedCannotSmuggleExecutionMetadata() {
        for (key in listOf("recursiveEvent", "stageRunId", "stageState",
            "receiptId", "childGraphId", "parentGraphId")) {
            val metadata = buildJsonObject {
                put("graphId", "graph-root")
                put(key, "opaque")
            }
            assertNull(PreExecutionPhasePolicy.step(
                event(metadata = metadata), "created"), key)
            assertNull(PreExecutionPhasePolicy.step(
                event(metadata = metadata), "accepted"), key)
        }
        assertEquals("created", PreExecutionPhasePolicy.step(
            event(metadata = buildJsonObject {}), "created"))
        assertNull(PreExecutionPhasePolicy.step(event(actor = buildJsonObject {
            put("agentId", "agent-one")
            put("futureExecutionActor", "child")
        }), "created"))
        assertEquals("created", PreExecutionPhasePolicy.step(
            event(actor = buildJsonObject {
                put("agentId", "agent-one")
                put("parentAgentId", JsonNull)
            }), "created"))
    }

    @Test fun realRecursiveAndPreparationEventsCannotAuthorizeAdmission() {
        val recorded = mutableListOf<JsonObject>()
        val sink = object : EventSink {
            override fun append(event: JsonObject) { recorded += event }
            override fun flush() = Unit
            override fun sha256(): String = "a".repeat(64)
            override fun close() = Unit
        }
        val journal = SessionEventJournal("session-113", "trace-113", "request-113",
            java.time.Clock.fixed(java.time.Instant.parse("2026-11-17T10:00:00Z"),
                java.time.ZoneOffset.UTC), sink)
        CorrelatedRecursiveSessionEvents(journal).emit(RecursiveEvent(
            RecursiveEventKind.CHILD_RESERVED, GraphId("graph-child"),
            StageRunId("stage-child"), GraphId("graph-root"),
            StageRunId("stage-parent"), "request-child", StageKind.SDD,
            RecursiveUsage(), RecursiveUsage(), RecursiveUsage(),
            childGraphId = GraphId("graph-child")))
        val correlation = StageCorrelation(
            "session-113", GraphId("graph-root"), StageRunId("stage-root"),
            "request-preparation", "correlation-preparation")
        val emitter = CorrelatedPreparationSessionEvents(journal)
        emitter.emit(PreparationStageEvent(correlation, StageKind.SDD, StageState.CREATED))
        emitter.emit(PreparationStageEvent(correlation, StageKind.SDD, StageState.READY))
        assertEquals(3, recorded.size)
        for (actual in recorded) {
            assertEquals("session.transition", actual["type"]?.jsonPrimitive?.content)
            assertNull(PreExecutionPhasePolicy.step(actual, "created"))
            assertNull(PreExecutionPhasePolicy.phaseFromVerifiedHistory(
                "created", listOf(actual)))
        }
        journal.finish()
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

package ai.yuriilab.recursivecoder

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The stage-event adapters only emit metadata allowed by Session Protocol v1. */
@Tag("integration")
class CorrelatedSessionEventsIntegrationTest {
    private class MemorySink : EventSink {
        val events = mutableListOf<JsonObject>()
        override fun append(event: JsonObject) { events += event }
        override fun flush() = Unit
        override fun sha256(): String = "a".repeat(64)
        override fun close() = Unit
    }

    private val clock = Clock.fixed(Instant.parse("2026-11-12T12:00:00Z"),
        ZoneOffset.UTC)

    @Test fun childRoleReviewEventsAreOrderedCorrelatedAndRedacted() {
        val sink = MemorySink()
        val journal = SessionEventJournal("session-79", "trace-79", "request-79",
            clock, sink)
        val childGraph = GraphId("graph-child-79")
        val parentGraph = GraphId("graph-parent-79")
        val childStage = StageRunId("stage-child-79")
        val parentStage = StageRunId("stage-parent-79")
        val correlation = StageCorrelation("session-79", childGraph, childStage,
            "request-child-79", "correlation-child-79")
        val roleSink = CorrelatedRoleSessionEvents(journal)
        val recursiveSink = CorrelatedRecursiveSessionEvents(journal)
        val preparationSink = CorrelatedPreparationSessionEvents(journal)
        val reviewerSink = CorrelatedReviewerSessionEvents(journal)

        roleSink.emit(StageRoleEvent(correlation, EngineeringRole.SDD,
            AgentId("actor-sdd-79"), StageRoleEventKind.STARTED, StageRoleUsage(1, null, null)))
        recursiveSink.emit(RecursiveEvent(RecursiveEventKind.CHILD_RESERVED, childGraph,
            childStage, parentGraph, parentStage, "request-child-79",
            StageKind.SDD, RecursiveUsage(1, 0), RecursiveUsage(2, 1),
            RecursiveUsage(8, 3), childGraphId = childGraph))
        preparationSink.emit(PreparationStageEvent(correlation, StageKind.SDD,
            StageState.ACCEPTED, ArtifactApproval.ACCEPTED, ArtifactId("artifact-79"),
            "b".repeat(64)))
        val reviewCorrelation = correlation.copy(stageRunId = StageRunId("stage-review-79"),
            requestId = "request-review-79")
        reviewerSink.emit(ReviewerDecisionRecord(reviewCorrelation,
            ReviewerDisposition.ACCEPT, "OK", ReviewerCorrectionScope.NONE,
            setOf("check:offline", "write:trusted"), "c".repeat(64)))

        assertEquals(4, sink.events.size)
        assertEquals(listOf(1, 2, 3, 4),
            sink.events.map { it["sequence"]?.jsonPrimitive?.content?.toInt() })
        assertEquals(listOf("request-child-79", "request-child-79",
            "request-child-79", "request-review-79"),
            sink.events.map { it["correlationId"]?.jsonPrimitive?.content })
        assertEquals("agent-stage-child-79",
            sink.events[1]["actor"]!!.jsonObject["agentId"]!!.jsonPrimitive.content)
        assertEquals("agent-stage-parent-79",
            sink.events[1]["actor"]!!.jsonObject["parentAgentId"]!!.jsonPrimitive.content)
        assertEquals("graph-child-79",
            sink.events[1]["metadata"]!!.jsonObject["childGraphId"]!!.jsonPrimitive.content)
        assertEquals("agent-stage-graph-child-79",
            sink.events[1]["actor"]!!.jsonObject["childAgentId"]!!.jsonPrimitive.content)
        assertEquals(JsonNull,
            sink.events[1]["actor"]!!.jsonObject["childSessionId"])
        assertFalse(sink.events[0]["metadata"]!!.jsonObject.containsKey("inputTokens"))
        assertFalse(sink.events[0]["metadata"]!!.jsonObject.containsKey("outputTokens"))
        assertEquals("ACCEPT",
            sink.events[3]["metadata"]!!.jsonObject["verdict"]!!.jsonPrimitive.content)
        for (event in sink.events) {
            assertEquals("session-event", event["kind"]!!.jsonPrimitive.content)
            assertEquals("1.0", event["version"]!!.jsonPrimitive.content)
            assertEquals("metadata-only",
                event["redaction"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
            assertFalse(event.toString().contains("TOP_SECRET"))
            assertFalse(event.toString().contains("env:"))
            assertTrue(event["causationId"] != null)
        }
        assertEquals("a".repeat(64), journal.finish())
    }

    @Test fun legacyActorAndCorrelationRemainUnchangedWithoutOverrides() {
        val sink = MemorySink()
        val journal = SessionEventJournal("legacy-79", "trace-79", "request-79",
            clock, sink)
        journal.append("agent.started", "started")
        val event = sink.events.single()
        assertEquals("agent-legacy-79",
            event["actor"]!!.jsonObject["agentId"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, event["actor"]!!.jsonObject["parentAgentId"])
        assertEquals("request-79", event["correlationId"]!!.jsonPrimitive.content)
        journal.finish()
    }
}

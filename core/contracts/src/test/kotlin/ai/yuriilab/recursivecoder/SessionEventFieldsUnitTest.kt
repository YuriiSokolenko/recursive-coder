package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@Tag("unit")
class SessionEventFieldsUnitTest {
    @Test fun eventAndResumeFieldsRetainWireValues() {
        assertEquals(listOf("eventId", "sequence", "timestamp", "type", "status", "correlationId", "causationId", "actor", "transition", "timing", "redaction", "metadata"),
            listOf(SessionEventFields.EVENT_ID, SessionEventFields.SEQUENCE, SessionEventFields.TIMESTAMP, SessionEventFields.TYPE, SessionEventFields.STATUS, SessionEventFields.CORRELATION_ID, SessionEventFields.CAUSATION_ID, SessionEventFields.ACTOR, SessionEventFields.TRANSITION, SessionEventFields.TIMING, SessionEventFields.REDACTION, SessionEventFields.METADATA))
        assertEquals(listOf("agentId", "parentAgentId", "childAgentId", "toolExecutionId", "childSessionId"),
            listOf(SessionEventActorFields.AGENT_ID, SessionEventActorFields.PARENT_AGENT_ID, SessionEventActorFields.CHILD_AGENT_ID, SessionEventActorFields.TOOL_EXECUTION_ID, SessionEventActorFields.CHILD_SESSION_ID))
        assertEquals(listOf("from", "to"),
            listOf(SessionEventTransitionFields.FROM, SessionEventTransitionFields.TO))
        assertEquals(listOf("startedAt", "durationMs"),
            listOf(SessionEventTimingFields.STARTED_AT, SessionEventTimingFields.DURATION_MS))
        assertEquals(listOf("mode", "removedFields"),
            listOf(SessionEventRedactionFields.MODE, SessionEventRedactionFields.REMOVED_FIELDS))
        assertEquals(listOf("path", "sha256"),
            listOf(SessionArtifactReferenceFields.PATH, SessionArtifactReferenceFields.SHA256))
        assertEquals(listOf("fresh", "resume"),
            listOf(SessionResumeModes.FRESH, SessionResumeModes.RESUME))
        assertEquals("metadata-only", SessionEventRedactionMode.METADATA_ONLY)
    }
}

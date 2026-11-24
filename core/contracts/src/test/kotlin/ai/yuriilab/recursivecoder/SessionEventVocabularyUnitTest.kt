package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@Tag("unit")
class SessionEventVocabularyUnitTest {
    @Test fun eventKindsAndStatusesRetainV1Spelling() {
        assertEquals(listOf("session.transition", "session.resumed", "agent.started", "agent.completed", "llm.capabilities", "llm.request", "llm.response", "tool.started", "tool.completed", "checkpoint.saved", "policy.denied", "session.error", "redaction.applied"),
            listOf(SessionEventKinds.SESSION_TRANSITION, SessionEventKinds.SESSION_RESUMED, SessionEventKinds.AGENT_STARTED, SessionEventKinds.AGENT_COMPLETED, SessionEventKinds.LLM_CAPABILITIES, SessionEventKinds.LLM_REQUEST, SessionEventKinds.LLM_RESPONSE, SessionEventKinds.TOOL_STARTED, SessionEventKinds.TOOL_COMPLETED, SessionEventKinds.CHECKPOINT_SAVED, SessionEventKinds.POLICY_DENIED, SessionEventKinds.SESSION_ERROR, SessionEventKinds.REDACTION_APPLIED))
        assertEquals(listOf("started", "completed", "failed", "denied", "cancelled", "timed_out"),
            listOf(SessionEventStatuses.STARTED, SessionEventStatuses.COMPLETED, SessionEventStatuses.FAILED, SessionEventStatuses.DENIED, SessionEventStatuses.CANCELLED, SessionEventStatuses.TIMED_OUT))
    }
}

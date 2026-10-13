package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Stable v1 diagnostics projection for every typed terminal state, without I/O. */
@Tag("unit")
class SessionResultMapperUnitTest {
    @Test fun diagnosticMessagesAreFixedAndDoNotIncorporateInboundRequestText() {
        val expected = mapOf(
            SessionOutcome.SUCCEEDED to "Read-only model session completed",
            SessionOutcome.CANCELLED to "Session cancelled",
            SessionOutcome.TIMED_OUT to "Session deadline exceeded",
            SessionOutcome.REJECTED to "Session rejected before model invocation",
            SessionOutcome.FAILED to "Session failed"
        )
        expected.forEach { (status, summary) ->
            val json = SessionResultMapper.diagnostics("session-one", status, 14)
            assertEquals("session-one", json.textAt("sessionId"))
            assertEquals(status.wire, json.textAt("status"))
            assertEquals(14L, json.longAt("events"))
            assertEquals(summary, json.textAt("summary"))
        }
    }
}

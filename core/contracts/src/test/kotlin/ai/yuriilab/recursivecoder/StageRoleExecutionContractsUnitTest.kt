package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pure in-memory identity and metadata regression for the one-role port. */
@Tag("unit")
class StageRoleExecutionContractsUnitTest {
    @Test fun denialDoesNotInventAnActorOrStageApproval() {
        val correlation = StageCorrelation(
            sessionId = "session-73", graphId = GraphId("graph-73"),
            stageRunId = StageRunId("unknown-73"),
            requestId = "request-73", correlationId = "correlation-73"
        )
        val usage = StageRoleUsage()
        val event = StageRoleEvent(
            correlation = correlation,
            role = EngineeringRole.ATDD,
            agentId = null,
            kind = StageRoleEventKind.DENIED,
            usage = usage,
            code = "UNKNOWN_STAGE"
        )
        val result = StageRoleResult(
            correlation = correlation,
            role = EngineeringRole.ATDD,
            agentId = null,
            outcome = StageRoleOutcome.Denied("UNKNOWN_STAGE"),
            usage = usage
        )
        assertNull(event.agentId)
        assertNull(result.agentId)
        assertEquals("request-73", event.correlation.requestId)
        assertEquals("graph-73", event.correlation.graphId.value)
        assertEquals(StageRoleOutcome.Denied(event.code!!), result.outcome)
        assertEquals(0, result.usage.modelRequests)
    }
}

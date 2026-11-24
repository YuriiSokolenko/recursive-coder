package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("unit")
class RunFiveRoleWorkflowUseCaseDiagnosticCodesUnitTest {
    @Test
    fun exactDiagnosticCodes() {
        assertEquals("HOST_SNAPSHOT_UNAVAILABLE", RunFiveRoleWorkflowUseCaseDiagnosticCodes.HOST_SNAPSHOT_UNAVAILABLE)
        assertEquals("GRAPH_TOKEN_BUDGET_EXHAUSTED", RunFiveRoleWorkflowUseCaseDiagnosticCodes.GRAPH_TOKEN_BUDGET_EXHAUSTED)
        assertEquals("WORKFLOW_OUTPUT_MISMATCH", RunFiveRoleWorkflowUseCaseDiagnosticCodes.WORKFLOW_OUTPUT_MISMATCH)
        assertEquals("MISSING_COMMITTED_GRAPH", RunFiveRoleWorkflowUseCaseDiagnosticCodes.MISSING_COMMITTED_GRAPH)
        assertEquals("MISSING_HOST_ACCEPTANCE", RunFiveRoleWorkflowUseCaseDiagnosticCodes.MISSING_HOST_ACCEPTANCE)
        assertEquals("STAGE_RECONCILIATION_UNCONFIRMED", RunFiveRoleWorkflowUseCaseDiagnosticCodes.STAGE_RECONCILIATION_UNCONFIRMED)
        assertEquals("GRAPH_FINISH_UNCONFIRMED", RunFiveRoleWorkflowUseCaseDiagnosticCodes.GRAPH_FINISH_UNCONFIRMED)
    }
}

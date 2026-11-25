package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("unit")
class StageGraphPolicyDiagnosticCodesUnitTest {
    @Test
    fun exactDiagnosticCodes() {
        assertEquals("SHARED_ROLE_ACTOR", StageGraphPolicyDiagnosticCodes.SHARED_ROLE_ACTOR)
        assertEquals("INVALID_STAGE_SUPERSESSION", StageGraphPolicyDiagnosticCodes.INVALID_STAGE_SUPERSESSION)
        assertEquals("INVALID_STAGE_REVIEW", StageGraphPolicyDiagnosticCodes.INVALID_STAGE_REVIEW)
        assertEquals("UNACCEPTED_STAGE_OUTPUT", StageGraphPolicyDiagnosticCodes.UNACCEPTED_STAGE_OUTPUT)
        assertEquals("GRAPH_NOT_RUNNING", StageGraphPolicyDiagnosticCodes.GRAPH_NOT_RUNNING)
        assertEquals("INVALID_INDEPENDENT_REVIEW", StageGraphPolicyDiagnosticCodes.INVALID_INDEPENDENT_REVIEW)
        assertEquals("GRAPH_NOT_ACCEPTED", StageGraphPolicyDiagnosticCodes.GRAPH_NOT_ACCEPTED)
    }
}

package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("unit")
class KoogStageRoleExecutorDiagnosticCodesUnitTest {
    @Test
    fun exactDiagnosticCodes() {
        assertEquals("EVENT_SINK_FAILED", KoogStageRoleExecutorDiagnosticCodes.EVENT_SINK_FAILED)
        assertEquals("ROLE_IDENTITY_MISMATCH", KoogStageRoleExecutorDiagnosticCodes.ROLE_IDENTITY_MISMATCH)
        assertEquals("ROLE_TOOL_SURFACE_UNAVAILABLE", KoogStageRoleExecutorDiagnosticCodes.ROLE_TOOL_SURFACE_UNAVAILABLE)
        assertEquals("INVALID_ROLE_BUDGET_OR_INPUT", KoogStageRoleExecutorDiagnosticCodes.INVALID_ROLE_BUDGET_OR_INPUT)
        assertEquals("MODEL_BRIDGE_UNSUPPORTED", KoogStageRoleExecutorDiagnosticCodes.MODEL_BRIDGE_UNSUPPORTED)
    }
}

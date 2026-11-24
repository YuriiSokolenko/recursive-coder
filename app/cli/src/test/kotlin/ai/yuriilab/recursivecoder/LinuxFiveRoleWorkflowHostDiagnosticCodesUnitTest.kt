package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("unit")
class LinuxFiveRoleWorkflowHostDiagnosticCodesUnitTest {
    @Test
    fun exactDiagnosticCodes() {
        assertEquals("CREATE_PREIMAGE_EXISTS", LinuxFiveRoleWorkflowHostDiagnosticCodes.CREATE_PREIMAGE_EXISTS)
        assertEquals("EDIT_PREIMAGE_CHANGED", LinuxFiveRoleWorkflowHostDiagnosticCodes.EDIT_PREIMAGE_CHANGED)
        assertEquals("RECOVERY_PREIMAGE_UNAVAILABLE", LinuxFiveRoleWorkflowHostDiagnosticCodes.RECOVERY_PREIMAGE_UNAVAILABLE)
        assertEquals("AUTHORITATIVE_ACCEPTANCE_UNCONFIRMED", LinuxFiveRoleWorkflowHostDiagnosticCodes.AUTHORITATIVE_ACCEPTANCE_UNCONFIRMED)
    }
}

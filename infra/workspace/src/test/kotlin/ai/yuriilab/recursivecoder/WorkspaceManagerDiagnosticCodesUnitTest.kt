package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("unit")
class WorkspaceManagerDiagnosticCodesUnitTest {
    @Test
    fun exactDiagnosticCodes() {
        assertEquals("READ_LIMIT_EXCEEDED", WorkspaceManagerDiagnosticCodes.READ_LIMIT_EXCEEDED)
        assertEquals("INVALID_TASK_PATH", WorkspaceManagerDiagnosticCodes.INVALID_TASK_PATH)
        assertEquals("RESULT_LIMIT_EXCEEDED", WorkspaceManagerDiagnosticCodes.RESULT_LIMIT_EXCEEDED)
        assertEquals("GIT_OUTPUT_INVALID", WorkspaceManagerDiagnosticCodes.GIT_OUTPUT_INVALID)
        assertEquals("GIT_INSPECTION_DENIED", WorkspaceManagerDiagnosticCodes.GIT_INSPECTION_DENIED)
    }
}

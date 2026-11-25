package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("unit")
class InstalledRecursiveWorkflowDiagnosticCodesUnitTest {
    @Test
    fun exactDiagnosticCodes() {
        assertEquals("AUTHENTICATED_CHILD_RECEIPT_MISSING", InstalledRecursiveWorkflowDiagnosticCodes.AUTHENTICATED_CHILD_RECEIPT_MISSING)
    }
}

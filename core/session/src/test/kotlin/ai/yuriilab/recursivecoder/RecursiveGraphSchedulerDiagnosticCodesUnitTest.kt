package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("unit")
class RecursiveGraphSchedulerDiagnosticCodesUnitTest {
    @Test
    fun exactDiagnosticCodes() {
        assertEquals("CHILD_EVIDENCE_UNAVAILABLE", RecursiveGraphSchedulerDiagnosticCodes.CHILD_EVIDENCE_UNAVAILABLE)
        assertEquals("INTEGRATION_ATTEMPT_LIMIT", RecursiveGraphSchedulerDiagnosticCodes.INTEGRATION_ATTEMPT_LIMIT)
        assertEquals("STAGE_EXECUTOR_FAILED", RecursiveGraphSchedulerDiagnosticCodes.STAGE_EXECUTOR_FAILED)
    }
}

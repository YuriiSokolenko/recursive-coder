package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("unit")
class RecursiveChildIntegrationPolicyDiagnosticCodesUnitTest {
    @Test
    fun exactDiagnosticCodes() {
        assertEquals("CHILD_ARTIFACT_UNACCEPTED", RecursiveChildIntegrationPolicyDiagnosticCodes.CHILD_ARTIFACT_UNACCEPTED)
    }
}

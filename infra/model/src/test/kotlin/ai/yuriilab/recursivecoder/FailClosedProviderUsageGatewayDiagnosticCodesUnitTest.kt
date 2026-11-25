package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("unit")
class FailClosedProviderUsageGatewayDiagnosticCodesUnitTest {
    @Test
    fun exactDiagnosticCodes() {
        assertEquals("MODEL_REQUEST_TIMEOUT_INVALID", FailClosedProviderUsageGatewayDiagnosticCodes.MODEL_REQUEST_TIMEOUT_INVALID)
    }
}

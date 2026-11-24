package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("unit")
class ModelGatewayDiagnosticCodesUnitTest {
    @Test
    fun exactDiagnosticCodes() {
        assertEquals("MODEL_RETRY_EXHAUSTED", ModelGatewayDiagnosticCodes.MODEL_RETRY_EXHAUSTED)
        assertEquals("MODEL_STREAM_INCOMPLETE", ModelGatewayDiagnosticCodes.MODEL_STREAM_INCOMPLETE)
        assertEquals("MODEL_TRANSPORT_FAILED", ModelGatewayDiagnosticCodes.MODEL_TRANSPORT_FAILED)
    }
}

package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("unit")
class RunDeveloperStageUseCaseDiagnosticCodesUnitTest {
    @Test
    fun exactDiagnosticCodes() {
        assertEquals("UNSUPPORTED_DEVELOPER_STAGE", RunDeveloperStageUseCaseDiagnosticCodes.UNSUPPORTED_DEVELOPER_STAGE)
        assertEquals("TARGET_READ_UNAVAILABLE", RunDeveloperStageUseCaseDiagnosticCodes.TARGET_READ_UNAVAILABLE)
        assertEquals("STALE_TARGET_BASELINE", RunDeveloperStageUseCaseDiagnosticCodes.STALE_TARGET_BASELINE)
        assertEquals("CREATE_TARGET_EXISTS", RunDeveloperStageUseCaseDiagnosticCodes.CREATE_TARGET_EXISTS)
        assertEquals("TARGET_ENCODING_INVALID", RunDeveloperStageUseCaseDiagnosticCodes.TARGET_ENCODING_INVALID)
        assertEquals("INVALID_DEVELOPER_PROPOSAL", RunDeveloperStageUseCaseDiagnosticCodes.INVALID_DEVELOPER_PROPOSAL)
    }
}

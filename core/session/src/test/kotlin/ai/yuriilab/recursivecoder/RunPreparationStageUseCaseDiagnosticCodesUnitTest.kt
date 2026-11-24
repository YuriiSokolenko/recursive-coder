package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("unit")
class RunPreparationStageUseCaseDiagnosticCodesUnitTest {
    @Test
    fun exactDiagnosticCodes() {
        assertEquals("UNSUPPORTED_PREPARATION_STAGE", RunPreparationStageUseCaseDiagnosticCodes.UNSUPPORTED_PREPARATION_STAGE)
        assertEquals("STALE_ARTIFACT_APPROVAL", RunPreparationStageUseCaseDiagnosticCodes.STALE_ARTIFACT_APPROVAL)
        assertEquals("HUMAN_APPROVAL_STOPPED", RunPreparationStageUseCaseDiagnosticCodes.HUMAN_APPROVAL_STOPPED)
        assertEquals("HUMAN_APPROVAL_TIMED_OUT", RunPreparationStageUseCaseDiagnosticCodes.HUMAN_APPROVAL_TIMED_OUT)
        assertEquals("NO_PENDING_HUMAN_APPROVAL", RunPreparationStageUseCaseDiagnosticCodes.NO_PENDING_HUMAN_APPROVAL)
        assertEquals("INVALID_HUMAN_APPROVAL_LEASE", RunPreparationStageUseCaseDiagnosticCodes.INVALID_HUMAN_APPROVAL_LEASE)
        assertEquals("HUMAN_APPROVAL_AUTHORITY_UNAVAILABLE", RunPreparationStageUseCaseDiagnosticCodes.HUMAN_APPROVAL_AUTHORITY_UNAVAILABLE)
        assertEquals("STALE_HUMAN_APPROVAL", RunPreparationStageUseCaseDiagnosticCodes.STALE_HUMAN_APPROVAL)
        assertEquals("HUMAN_APPROVAL_READ_UNAVAILABLE", RunPreparationStageUseCaseDiagnosticCodes.HUMAN_APPROVAL_READ_UNAVAILABLE)
    }
}

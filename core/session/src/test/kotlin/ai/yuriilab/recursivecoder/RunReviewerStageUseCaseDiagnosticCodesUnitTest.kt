package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("unit")
class RunReviewerStageUseCaseDiagnosticCodesUnitTest {
    @Test
    fun exactDiagnosticCodes() {
        assertEquals("INVALID_REVIEW_RESPONSE", RunReviewerStageUseCaseDiagnosticCodes.INVALID_REVIEW_RESPONSE)
        assertEquals("REVIEW_FINISH_UNCONFIRMED", RunReviewerStageUseCaseDiagnosticCodes.REVIEW_FINISH_UNCONFIRMED)
    }
}

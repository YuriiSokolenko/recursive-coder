package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("unit")
class ReviewerStageContractsDiagnosticCodesUnitTest {
    @Test
    fun exactDiagnosticCodes() {
        assertEquals("REVIEW_MUTATED_OTHER_STATE", ReviewerStageContractsDiagnosticCodes.REVIEW_MUTATED_OTHER_STATE)
        assertEquals("UNAUTHORIZED_REPAIR", ReviewerStageContractsDiagnosticCodes.UNAUTHORIZED_REPAIR)
    }
}

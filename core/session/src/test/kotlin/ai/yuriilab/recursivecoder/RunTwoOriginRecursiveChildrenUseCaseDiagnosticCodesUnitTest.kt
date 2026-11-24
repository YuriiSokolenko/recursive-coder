package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("unit")
class RunTwoOriginRecursiveChildrenUseCaseDiagnosticCodesUnitTest {
    @Test
    fun exactDiagnosticCodes() {
        assertEquals("INVALID_ORIGIN_PAIR", RunTwoOriginRecursiveChildrenUseCaseDiagnosticCodes.INVALID_ORIGIN_PAIR)
    }
}

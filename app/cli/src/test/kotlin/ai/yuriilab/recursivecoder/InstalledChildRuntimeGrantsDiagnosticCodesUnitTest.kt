package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("unit")
class InstalledChildRuntimeGrantsDiagnosticCodesUnitTest {
    @Test
    fun exactDiagnosticCodes() {
        assertEquals("CHILD_USAGE_INVALID", InstalledChildRuntimeGrantsDiagnosticCodes.CHILD_USAGE_INVALID)
    }
}

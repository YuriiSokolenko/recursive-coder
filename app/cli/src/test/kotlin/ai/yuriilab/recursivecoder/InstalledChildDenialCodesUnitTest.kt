package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@Tag("unit")
class InstalledChildDenialCodesUnitTest {
    @Test fun codesRetainDiagnosticSpelling() {
        assertEquals("CHILD_USAGE_UNCONFIRMED", InstalledChildDenialCodes.CHILD_USAGE_UNCONFIRMED)
        assertEquals("CHILD_EXECUTION_UNCONFIRMED", InstalledChildDenialCodes.CHILD_EXECUTION_UNCONFIRMED)
    }
}

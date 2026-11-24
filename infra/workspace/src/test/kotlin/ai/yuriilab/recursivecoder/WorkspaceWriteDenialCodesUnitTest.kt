package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@Tag("unit")
class WorkspaceWriteDenialCodesUnitTest {
    @Test fun codesRetainDiagnosticSpelling() {
        assertEquals("WRITE_SESSION_INVALID", WorkspaceWriteDenialCodes.WRITE_SESSION_INVALID)
        assertEquals("UNSAFE_GIT_INDEX", WorkspaceWriteDenialCodes.UNSAFE_GIT_INDEX)
        assertEquals("CHECK_UNAVAILABLE", WorkspaceWriteDenialCodes.CHECK_UNAVAILABLE)
    }
}

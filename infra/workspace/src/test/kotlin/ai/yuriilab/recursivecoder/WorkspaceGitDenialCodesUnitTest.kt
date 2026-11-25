package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@Tag("unit")
class WorkspaceGitDenialCodesUnitTest {
    @Test fun codesRetainDiagnosticSpelling() {
        assertEquals("GIT_TIMEOUT", WorkspaceGitDenialCodes.GIT_TIMEOUT)
        assertEquals("GIT_STATUS_INVALID", WorkspaceGitDenialCodes.GIT_STATUS_INVALID)
    }
}

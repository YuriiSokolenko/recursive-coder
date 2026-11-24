package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("unit")
class ResumeFenceAnchorDiagnosticCodesUnitTest {
    @Test
    fun exactDiagnosticCodes() {
        assertEquals("RESUME_FENCE_HARD_LINK_PROBE_CLEANUP_UNCONFIRMED", ResumeFenceAnchorDiagnosticCodes.RESUME_FENCE_HARD_LINK_PROBE_CLEANUP_UNCONFIRMED)
        assertEquals("RESUME_FENCE_ANCHOR_UNSAFE_DIRECTORY", ResumeFenceAnchorDiagnosticCodes.RESUME_FENCE_ANCHOR_UNSAFE_DIRECTORY)
        assertEquals("RESUME_FENCE_AUDIT_CONFLICT", ResumeFenceAnchorDiagnosticCodes.RESUME_FENCE_AUDIT_CONFLICT)
    }
}

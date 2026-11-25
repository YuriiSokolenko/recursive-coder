package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@Tag("unit")
class ResumeDenialCodesUnitTest {
    @Test fun resumeCodesRetainDiagnosticSpelling() {
        assertEquals("RESUME_FENCE_ANCHOR_UNCONFIRMED", ResumeDenialCodes.RESUME_FENCE_ANCHOR_UNCONFIRMED)
        assertEquals("RESUME_FENCE_RECONCILIATION_UNCONFIRMED", ResumeDenialCodes.RESUME_FENCE_RECONCILIATION_UNCONFIRMED)
        assertEquals("RESUME_FENCE_CAS_FAILED", ResumeDenialCodes.RESUME_FENCE_CAS_FAILED)
        assertEquals("RESUME_FENCE_STALE_WRITER", ResumeDenialCodes.RESUME_FENCE_STALE_WRITER)
        assertEquals("RESUME_UNSAFE_BOUNDARY", ResumeDenialCodes.RESUME_UNSAFE_BOUNDARY)
        assertEquals("RESUME_FENCE_PUBLISH_UNCONFIRMED", ResumeDenialCodes.RESUME_FENCE_PUBLISH_UNCONFIRMED)
        assertEquals("RESUME_FENCE_AUDIT_UNCONFIRMED", ResumeDenialCodes.RESUME_FENCE_AUDIT_UNCONFIRMED)
        assertEquals("RESUME_FENCE_ANCHOR_MISMATCH", ResumeDenialCodes.RESUME_FENCE_ANCHOR_MISMATCH)
        assertEquals("RESUME_FENCE_CORRUPT", ResumeDenialCodes.RESUME_FENCE_CORRUPT)
        assertEquals("RESUME_CHECKPOINT_CHANGED", ResumeDenialCodes.RESUME_CHECKPOINT_CHANGED)
        assertEquals("RESUME_FILESYSTEM_UNSUPPORTED", ResumeDenialCodes.RESUME_FILESYSTEM_UNSUPPORTED)
        assertEquals("RESUME_ADMISSION_UNCONFIRMED", ResumeDenialCodes.RESUME_ADMISSION_UNCONFIRMED)
    }
}

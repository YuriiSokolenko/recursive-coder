package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("unit")
class SessionResumeAdmissionDiagnosticCodesUnitTest {
    @Test
    fun exactDiagnosticCodes() {
        assertEquals("RESUME_FENCE_LIMIT", SessionResumeAdmissionDiagnosticCodes.RESUME_FENCE_LIMIT)
        assertEquals("RESUME_FENCE_CLEANUP_UNCONFIRMED", SessionResumeAdmissionDiagnosticCodes.RESUME_FENCE_CLEANUP_UNCONFIRMED)
        assertEquals("RESUME_FENCE_CONTEXT_CHANGED", SessionResumeAdmissionDiagnosticCodes.RESUME_FENCE_CONTEXT_CHANGED)
        assertEquals("RESUME_LEDGER_PATH_CHANGED", SessionResumeAdmissionDiagnosticCodes.RESUME_LEDGER_PATH_CHANGED)
        assertEquals("RESUME_LEDGER_IDENTITY_UNCONFIRMED", SessionResumeAdmissionDiagnosticCodes.RESUME_LEDGER_IDENTITY_UNCONFIRMED)
        assertEquals("RESUME_EFFECT_RECONCILIATION_INVALID", SessionResumeAdmissionDiagnosticCodes.RESUME_EFFECT_RECONCILIATION_INVALID)
        assertEquals("RESUME_LEASE_HELD", SessionResumeAdmissionDiagnosticCodes.RESUME_LEASE_HELD)
    }
}

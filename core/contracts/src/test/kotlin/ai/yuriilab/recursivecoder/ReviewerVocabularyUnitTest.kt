package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@Tag("unit")
class ReviewerVocabularyUnitTest {
    @Test fun proposalFieldsAndEvidenceGrammarRemainStable() {
        assertEquals("verdict", ReviewerProposalFields.VERDICT)
        assertEquals("code", ReviewerProposalFields.CODE)
        assertEquals("scope", ReviewerProposalFields.SCOPE)
        assertEquals("evidence", ReviewerProposalFields.EVIDENCE)
        assertEquals("verified-check", ReviewerPromptEvidenceTags.CHECK)
        assertEquals("verified-write", ReviewerPromptEvidenceTags.WRITE)
        assertEquals("check:", ReviewerEvidenceReferences.CHECK_PREFIX)
        assertEquals("write:", ReviewerEvidenceReferences.WRITE_PREFIX)
        assertEquals("file:", ReviewerEvidenceReferences.FILE_PREFIX)
    }

    @Test fun reasonCodesRetainWireSpelling() {
        assertEquals(setOf("OK", "IMPLEMENTATION_DEFECT", "FROZEN_ARTIFACT_CHANGE", "EVIDENCE_MISSING", "CHECK_FAILED", "OTHER_REQUIRES_HUMAN"),
            ReviewerReasonCode.entries.map { it.wire }.toSet())
        assertEquals("OK", ReviewerReasonCode.OK.wire)
        assertEquals("IMPLEMENTATION_DEFECT", ReviewerReasonCode.IMPLEMENTATION_DEFECT.wire)
        assertEquals("FROZEN_ARTIFACT_CHANGE", ReviewerReasonCode.FROZEN_ARTIFACT_CHANGE.wire)
        assertEquals("EVIDENCE_MISSING", ReviewerReasonCode.EVIDENCE_MISSING.wire)
        assertEquals("CHECK_FAILED", ReviewerReasonCode.CHECK_FAILED.wire)
        assertEquals("OTHER_REQUIRES_HUMAN", ReviewerReasonCode.OTHER_REQUIRES_HUMAN.wire)
    }

    @Test fun dispositionAndScopeRetainWireSpelling() {
        assertEquals(setOf("ACCEPT", "REJECT", "NEEDS_HUMAN"),
            ReviewerDisposition.entries.map { it.name }.toSet())
        assertEquals(setOf("NONE", "IMPLEMENTATION_ONLY", "FROZEN_INPUTS"),
            ReviewerCorrectionScope.entries.map { it.name }.toSet())
    }
}

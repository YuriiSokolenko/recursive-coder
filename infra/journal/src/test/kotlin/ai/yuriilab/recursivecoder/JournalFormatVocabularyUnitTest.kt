package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@Tag("unit")
class JournalFormatVocabularyUnitTest {
    @Test fun evidenceKeysAndFenceBoundRemainStable() {
        assertEquals(listOf("effectOperationId", "effectFenceGeneration", "effectKind", "effectInputSha256", "effectRecordSequence", "effectRecordMacSha256", "effectState", "effectObservedStateSha256", "effectErrorCode"),
            listOf(EffectEvidenceFields.EFFECT_OPERATION_ID, EffectEvidenceFields.EFFECT_FENCE_GENERATION, EffectEvidenceFields.EFFECT_KIND, EffectEvidenceFields.EFFECT_INPUT_SHA256, EffectEvidenceFields.EFFECT_RECORD_SEQUENCE, EffectEvidenceFields.EFFECT_RECORD_MAC_SHA256, EffectEvidenceFields.EFFECT_STATE, EffectEvidenceFields.EFFECT_OBSERVED_STATE_SHA256, EffectEvidenceFields.EFFECT_ERROR_CODE))
        assertEquals(listOf("intent", "started", "committed", "unconfirmed"),
            listOf(EffectRecordStates.INTENT, EffectRecordStates.STARTED,
                EffectRecordStates.COMMITTED, EffectRecordStates.UNCONFIRMED))
        assertEquals(256, ResumeFenceStorage.MAX_FENCES)
        assertEquals("checkpoint.lock", CHECKPOINT_LOCK_NAME)
        assertEquals("resume.lock", RESUME_LOCK_NAME)
    }
}

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

    @Test fun effectJournalCapacityRemainsStable() {
        assertEquals(100_000, EffectJournalLimits.MAX_RECORDS)
    }

    @Test fun checkpointTaskStateSpellingsRemainStable() {
        assertEquals(listOf("created", "ready", "blocked", "running", "paused",
            "succeeded", "failed", "needs-attention", "cancelled"),
            listOf(CheckpointTaskStates.CREATED, CheckpointTaskStates.READY,
                CheckpointTaskStates.BLOCKED, CheckpointTaskStates.RUNNING,
                CheckpointTaskStates.PAUSED, CheckpointTaskStates.SUCCEEDED,
                CheckpointTaskStates.FAILED, CheckpointTaskStates.NEEDS_ATTENTION,
                CheckpointTaskStates.CANCELLED))
    }

    @Test fun checkpointChildStateSpellingsMatchRecursiveNodeState() {
        val wires = setOf(CheckpointChildStates.READY, CheckpointChildStates.RUNNING,
            CheckpointChildStates.SUCCEEDED, CheckpointChildStates.FAILED,
            CheckpointChildStates.CANCELLED, CheckpointChildStates.TIMED_OUT)
        assertEquals(setOf("ready", "running", "succeeded", "failed",
            "cancelled", "timed_out"), wires)
        assertEquals(RecursiveNodeState.entries.map { it.name.lowercase() }.toSet(), wires)
    }

    @Test fun checkpointUsageVocabularyRemainsStable() {
        assertEquals(listOf("model", "tool"),
            listOf(CheckpointUsageKinds.MODEL, CheckpointUsageKinds.TOOL))
        assertEquals(listOf("known", "unknown"),
            listOf(CheckpointUsageCertainty.KNOWN, CheckpointUsageCertainty.UNKNOWN))
    }
}

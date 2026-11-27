package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@Tag("unit")
class JournalDiagnosticCodesUnitTest {
    @Test fun checkpointDiagnosticSpellingsRemainStable() {
        assertEquals("CHECKPOINT_WORKSPACE_INVALID",
            CheckpointDiagnosticCodes.CHECKPOINT_WORKSPACE_INVALID)
        assertEquals("CHECKPOINT_PAYLOAD_INVALID",
            CheckpointDiagnosticCodes.CHECKPOINT_PAYLOAD_INVALID)
        assertEquals("CHECKPOINT_VERSION_UNSUPPORTED",
            CheckpointDiagnosticCodes.CHECKPOINT_VERSION_UNSUPPORTED)
        assertEquals("CHECKPOINT_UNSAFE_DIRECTORY",
            CheckpointDiagnosticCodes.CHECKPOINT_UNSAFE_DIRECTORY)
        assertEquals("CHECKPOINT_UNSAFE_LOCK",
            CheckpointDiagnosticCodes.CHECKPOINT_UNSAFE_LOCK)
        assertEquals("CHECKPOINT_LEASE_RELEASE_UNCONFIRMED",
            CheckpointDiagnosticCodes.CHECKPOINT_LEASE_RELEASE_UNCONFIRMED)
        assertEquals("CHECKPOINT_SIZE_INVALID",
            CheckpointDiagnosticCodes.CHECKPOINT_SIZE_INVALID)
        assertEquals("CHECKPOINT_TRUNCATED",
            CheckpointDiagnosticCodes.CHECKPOINT_TRUNCATED)
        assertEquals("CHECKPOINT_UNRECOGNIZED_ENTRY",
            CheckpointDiagnosticCodes.CHECKPOINT_UNRECOGNIZED_ENTRY)
        assertEquals("CHECKPOINT_CHAIN_MISMATCH",
            CheckpointDiagnosticCodes.CHECKPOINT_CHAIN_MISMATCH)
        assertEquals("CHECKPOINT_EFFECT_RECONCILIATION_FENCE_MISMATCH",
            CheckpointDiagnosticCodes.CHECKPOINT_EFFECT_RECONCILIATION_FENCE_MISMATCH)
        assertEquals("CHECKPOINT_EFFECT_RECONCILIATION_SOURCE_MISMATCH",
            CheckpointDiagnosticCodes.CHECKPOINT_EFFECT_RECONCILIATION_SOURCE_MISMATCH)
        assertEquals("CHECKPOINT_EFFECT_HEAD_FENCE_MISMATCH",
            CheckpointDiagnosticCodes.CHECKPOINT_EFFECT_HEAD_FENCE_MISMATCH)
        assertEquals("CHECKPOINT_JOURNAL_UNCONFIRMED",
            CheckpointDiagnosticCodes.CHECKPOINT_JOURNAL_UNCONFIRMED)
        assertEquals("CHECKPOINT_LIVE_LEDGER_TOO_LARGE",
            CheckpointDiagnosticCodes.CHECKPOINT_LIVE_LEDGER_TOO_LARGE)
        assertEquals("CHECKPOINT_WRITE_UNCONFIRMED",
            CheckpointDiagnosticCodes.CHECKPOINT_WRITE_UNCONFIRMED)
        assertEquals("CHECKPOINT_EFFECT_RECONCILIATION_UNVERIFIED",
            CheckpointDiagnosticCodes.CHECKPOINT_EFFECT_RECONCILIATION_UNVERIFIED)
        assertEquals("CHECKPOINT_STALE_GENERATION",
            CheckpointDiagnosticCodes.CHECKPOINT_STALE_GENERATION)
        assertEquals("CHECKPOINT_RESUME_ANCHOR_UNCONFIRMED",
            CheckpointDiagnosticCodes.CHECKPOINT_RESUME_ANCHOR_UNCONFIRMED)
        assertEquals("CHECKPOINT_RUNNING_BOUNDARY_CHANGED",
            CheckpointDiagnosticCodes.CHECKPOINT_RUNNING_BOUNDARY_CHANGED)
        assertEquals("CHECKPOINT_FENCED_LEDGER_ADVANCED",
            CheckpointDiagnosticCodes.CHECKPOINT_FENCED_LEDGER_ADVANCED)
        assertEquals("CHECKPOINT_MISSING",
            CheckpointDiagnosticCodes.CHECKPOINT_MISSING)
    }

    @Test fun effectJournalDiagnosticSpellingsRemainStable() {
        assertEquals("EFFECT_JOURNAL_PIN_PUBLICATION_UNCONFIRMED",
            EffectJournalDiagnosticCodes.EFFECT_JOURNAL_PIN_PUBLICATION_UNCONFIRMED)
        assertEquals("EFFECT_JOURNAL_UNSAFE_PATH",
            EffectJournalDiagnosticCodes.EFFECT_JOURNAL_UNSAFE_PATH)
        assertEquals("EFFECT_JOURNAL_BUSY",
            EffectJournalDiagnosticCodes.EFFECT_JOURNAL_BUSY)
        assertEquals("EFFECT_JOURNAL_UNSAFE_LOCK",
            EffectJournalDiagnosticCodes.EFFECT_JOURNAL_UNSAFE_LOCK)
        assertEquals("EFFECT_JOURNAL_INVALID_RECORD",
            EffectJournalDiagnosticCodes.EFFECT_JOURNAL_INVALID_RECORD)
        assertEquals("EFFECT_JOURNAL_GENESIS_INVALID",
            EffectJournalDiagnosticCodes.EFFECT_JOURNAL_GENESIS_INVALID)
        assertEquals("EFFECT_JOURNAL_RECORD_CORRUPT",
            EffectJournalDiagnosticCodes.EFFECT_JOURNAL_RECORD_CORRUPT)
        assertEquals("EFFECT_JOURNAL_APPEND_UNCONFIRMED",
            EffectJournalDiagnosticCodes.EFFECT_JOURNAL_APPEND_UNCONFIRMED)
        assertEquals("EFFECT_JOURNAL_CONTEXT_MISMATCH",
            EffectJournalDiagnosticCodes.EFFECT_JOURNAL_CONTEXT_MISMATCH)
        assertEquals("EFFECT_JOURNAL_SIZE_INVALID",
            EffectJournalDiagnosticCodes.EFFECT_JOURNAL_SIZE_INVALID)
        assertEquals("EFFECT_JOURNAL_TRUNCATED",
            EffectJournalDiagnosticCodes.EFFECT_JOURNAL_TRUNCATED)
        assertEquals("EFFECT_JOURNAL_AUTH_FAILED",
            EffectJournalDiagnosticCodes.EFFECT_JOURNAL_AUTH_FAILED)
        assertEquals("EFFECT_JOURNAL_PATH_CHANGED",
            EffectJournalDiagnosticCodes.EFFECT_JOURNAL_PATH_CHANGED)
    }
}

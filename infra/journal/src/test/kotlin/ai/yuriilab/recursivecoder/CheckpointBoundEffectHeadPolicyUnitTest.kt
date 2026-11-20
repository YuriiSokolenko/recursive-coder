package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Pure MAC/authority selection. Deliberately no filesystem or forged
 * production checkpoint: the actual verifier always performs full physical
 * checkpoint/fence/anchor/sidecar inspections before returning a boolean.
 */
@Tag("unit")
class CheckpointBoundEffectHeadPolicyUnitTest {
    private val hostKey = ByteArray(32) { (it + 20).toByte() }
    private val id = EffectIdentity("sess-one", "effect-one", 1L,
        EffectKind.TRUSTED_WRITE, "a".repeat(64))
    private val historical = CheckpointEffectJournalHead(
        "sess-one", 1L, "b".repeat(64), "c".repeat(64),
        "d".repeat(64), 0L, "e".repeat(64))
    private val checkpointSha = "f".repeat(64)

    private fun sealedTransition(): VerifiedSessionCheckpoint {
        val unsigned = CheckpointEffectReconciliation(
            "1".repeat(64), 1L, "2".repeat(64), historical,
            "operator-one", "0".repeat(64))
        val snapshot = SessionCheckpoint(
            sequence = 3L,
            previousSha256 = "1".repeat(64),
            binding = CheckpointBinding(
                "3".repeat(64), "4".repeat(64), "git-head", "dev",
                "5".repeat(64), "graph-root", 1L, "6".repeat(64)),
            sessionId = "sess-one", traceId = "trace-one",
            phase = "accepted", taskId = "task-one", taskAttempt = 0,
            taskState = "ready", children = emptyList(),
            acceptedArtifacts = emptyList(), hostAssertedUsage = emptyList(),
            pendingEffects = emptyList(), journalSequence = 1,
            journalHeadSha256 = "7".repeat(64),
            journalFileSha256 = "8".repeat(64), lastTrustedJournalOffset = 100L,
            effectReconciliation = unsigned)
        return VerifiedSessionCheckpoint(
            snapshot.copy(effectReconciliation =
                GenesisEffectReconciliationSeal.seal(hostKey, unsigned, snapshot)),
            checkpointSha, false)
    }

    private fun select(
        checkpoint: VerifiedSessionCheckpoint, generation: Long = 2L,
        admitted: String = checkpointSha, identity: EffectIdentity = id,
        key: ByteArray = hostKey
    ): CheckpointEffectJournalHead? =
        CheckpointBoundEffectHeadPolicy.authenticatedHead(
            checkpoint, generation, admitted, identity, key)

    @Test fun authenticOperatorSealAllowsOnlyExactImmediateSuccessorGenesis() {
        val checkpoint = sealedTransition()
        assertEquals(historical, select(checkpoint))
        assertNull(select(checkpoint, generation = 1L))
        assertNull(select(checkpoint, generation = 3L))
        assertNull(select(checkpoint, admitted = "9".repeat(64)))
        assertNull(select(checkpoint, identity = id.copy(fenceGeneration = 2L)))
        assertNull(select(checkpoint, identity = id.copy(sessionId = "other-session")))
        assertNull(select(checkpoint.copy(snapshot = checkpoint.snapshot.copy(
            effectReconciliation = checkpoint.snapshot.effectReconciliation!!.copy(
                sourceHead = historical.copy(lastSequence = 1L))))))
    }

    @Test fun wrongSealIsRejectedWithCorrectHostKeyBeforePhysicalInspection() {
        val checkpoint = sealedTransition()
        assertEquals(historical, select(checkpoint))
        val original = checkpoint.snapshot.effectReconciliation!!
        val wrongSeal = checkpoint.copy(snapshot = checkpoint.snapshot.copy(
            effectReconciliation = original.copy(
                hostSealMacSha256 = "0".repeat(64))))
        assertEquals("RESUME_EFFECT_RECONCILIATION_MAC_MISMATCH",
            assertFailsWith<SessionResumeUnconfirmed> {
                select(wrongSeal)
            }.code)
        val tamperedSource = checkpoint.copy(snapshot = checkpoint.snapshot.copy(
            effectReconciliation = original.copy(
                sourceHead = historical.copy(headMacSha256 = "9".repeat(64)))))
        assertEquals("RESUME_EFFECT_RECONCILIATION_MAC_MISMATCH",
            assertFailsWith<SessionResumeUnconfirmed> {
                select(tamperedSource)
            }.code)
        assertEquals("RESUME_EFFECT_RECONCILIATION_MAC_MISMATCH",
            assertFailsWith<SessionResumeUnconfirmed> {
                select(checkpoint, key = ByteArray(32))
            }.code)
    }
}

package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Pure contract fixtures; the verifier stands in for future durable host proofs. */
@Tag("unit")
class EffectReconciliationPolicyUnitTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private val mac = "d".repeat(64)
    private val key = EffectIdentity("session-one", "operation-one", 3L,
        EffectKind.TRUSTED_WRITE, a)

    private fun intent(id: EffectIdentity = key) = EffectRecord.Intent(id)
    private fun started(id: EffectIdentity = key) = EffectRecord.Started(id)
    private fun committed(id: EffectIdentity = key) = EffectRecord.Committed(id, b)
    private fun unconfirmed(id: EffectIdentity = key) =
        EffectRecord.Unconfirmed(id, "HOST_CALL_UNCONFIRMED")
    private fun view(vararg records: EffectRecord): EffectJournalView =
        EffectJournalView(c, records.size.toLong() + 10L,
            records.mapIndexed { index, record ->
                EffectJournalEntry(index.toLong() + 1, record, mac)
            })
    private fun proof(id: EffectIdentity = key, history: EffectJournalView) =
        EffectNotStartedEvidence(id, history.headSha256, history.lastSequence, b, mac)

    private class Verifier : HostEffectEvidenceVerifier {
        var historyValid = true
        var committedVerified = true
        var absentVerified = true
        var failWithException = false
        override fun authenticateCompleteJournal(view: EffectJournalView): Boolean {
            if (failWithException) error("unavailable")
            return historyValid && view.entries.all { it.hostMacSha256 == "d".repeat(64) }
        }
        override fun independentlyVerifyCommitted(
            intent: EffectRecord.Intent, started: EffectRecord.Started,
            committed: EffectRecord.Committed
        ): Boolean {
            if (failWithException) error("unavailable")
            return committedVerified
        }
        override fun independentlyVerifyNotStarted(
            intent: EffectRecord.Intent, proof: EffectNotStartedEvidence
        ): Boolean {
            if (failWithException) error("unavailable")
            return absentVerified
        }
    }

    private fun recover(
        id: EffectIdentity, history: EffectJournalView,
        verifier: HostEffectEvidenceVerifier, proof: EffectNotStartedEvidence? = null
    ): EffectRecoveryOutcome =
        EffectReconciliationPolicy.reconcile(id, id.fenceGeneration, history, verifier, proof)

    private fun blocked(result: EffectRecoveryOutcome, code: String) {
        assertEquals(EffectRecoveryOutcome.Unconfirmed(code), result)
    }

    @Test fun verifiedCommittedRequiresOrderedIntentStartAndIndependentReceipt() {
        val history = view(intent(), started(), committed())
        assertEquals(EffectRecoveryOutcome.ConfirmedCommitted(committed()),
            recover(key, history, Verifier()))
        val verifier = Verifier().apply { committedVerified = false }
        blocked(recover(key, history, verifier),
            "EFFECT_COMMIT_UNVERIFIED")
    }

    @Test fun missingStartedIsNotProofOfAbsence() {
        val history = view(intent())
        blocked(recover(key, history, Verifier()),
            "EFFECT_NOT_STARTED_UNPROVEN")
        assertEquals(EffectRecoveryOutcome.ProvenNotStarted(proof(history = history)),
            recover(key, history, Verifier(),
                proof(history = history)))
        blocked(recover(key, history,
            Verifier().apply { absentVerified = false }, proof(history = history)),
            "EFFECT_NOT_STARTED_UNPROVEN")
    }

    @Test fun startedWithoutReceiptAndUnconfirmedAreAlwaysQuarantined() {
        blocked(recover(key, view(intent(), started()), Verifier()),
            "EFFECT_STARTED_WITHOUT_RECEIPT")
        for (records in listOf(
            arrayOf(intent(), unconfirmed()),
            arrayOf(intent(), started(), unconfirmed()),
            arrayOf(intent(), started(), unconfirmed(), committed()),
            arrayOf(intent(), started(), committed(), unconfirmed())
        )) {
            blocked(recover(key, view(*records), Verifier()),
                "EFFECT_RECORDED_UNCONFIRMED")
        }
    }

    @Test fun staleGenerationAndReusedOperationIdCannotChooseAWinningHistory() {
        blocked(EffectReconciliationPolicy.reconcile(key, 4L,
            view(intent(), started(), committed()), Verifier()), "EFFECT_STALE_GENERATION")
        val stale = key.copy(fenceGeneration = 2)
        blocked(recover(key, view(intent(stale)), Verifier()),
            "EFFECT_STALE_OR_REUSED_OPERATION")
        blocked(recover(key,
            view(intent(), intent(stale), started(), committed()), Verifier()),
            "EFFECT_STALE_OR_REUSED_OPERATION")
        val changedKind = key.copy(kind = EffectKind.GIT_CAS)
        blocked(recover(key,
            view(intent(), intent(changedKind)), Verifier()),
            "EFFECT_STALE_OR_REUSED_OPERATION")
    }

    @Test fun duplicateIntentsAndTerminalReceiptsFailClosed() {
        blocked(recover(key,
            view(intent(), intent(), started(), committed()), Verifier()),
            "EFFECT_DUPLICATE_OR_MISSING_INTENT")
        blocked(recover(key,
            view(intent(), started(), started(), committed()), Verifier()),
            "EFFECT_INVALID_ORDER")
        blocked(recover(key,
            view(intent(), started(), committed(), committed()), Verifier()),
            "EFFECT_INVALID_ORDER")
    }

    @Test fun noStartProofMustMatchExactJournalHeadAndHaveNoConflictingEvent() {
        val history = view(intent())
        blocked(recover(key, history, Verifier(),
            proof(history = history).copy(journalHeadSha256 = a)),
            "EFFECT_NONSTART_PROOF_MISMATCH")
        blocked(recover(key, history, Verifier(),
            proof(history = history).copy(journalLastSequence = history.lastSequence - 1)),
            "EFFECT_NONSTART_PROOF_MISMATCH")
        blocked(recover(key,
            view(intent(), started(), committed()), Verifier(), proof(history = history)),
            "EFFECT_CONFLICTING_PROOF")
        blocked(recover(key, view(intent(), started()),
            Verifier(), proof(history = history)), "EFFECT_CONFLICTING_PROOF")
    }

    @Test fun malformedMissingOrOutOfOrderEntriesNeverBecomeEvidence() {
        blocked(recover(key, view(), Verifier()),
            "EFFECT_INVALID_HISTORY")
        blocked(recover(key, view(started(), committed()), Verifier()),
            "EFFECT_DUPLICATE_OR_MISSING_INTENT")
        blocked(recover(key, view(committed(), intent()), Verifier()),
            "EFFECT_INVALID_ORDER")
        val history = view(intent(), started(), committed())
        blocked(recover(key, history.copy(
            entries = history.entries.mapIndexed { index, entry ->
                if (index == 1) entry.copy(sequence = 1) else entry
            }), Verifier()), "EFFECT_INVALID_HISTORY")
        blocked(recover(key, history.copy(
            entries = history.entries.mapIndexed { index, entry ->
                if (index == 2) entry.copy(hostMacSha256 = "forged") else entry
            }), Verifier()), "EFFECT_INVALID_HISTORY")
        blocked(recover(key, history.copy(headSha256 = "bad"),
            Verifier()), "EFFECT_INVALID_HISTORY")
    }

    @Test fun verifierFailuresAndExceptionsNeverConfirmAnything() {
        val history = view(intent(), started(), committed())
        blocked(recover(key, history,
            Verifier().apply { historyValid = false }), "EFFECT_HISTORY_UNVERIFIED")
        blocked(recover(key, history,
            Verifier().apply { failWithException = true }), "EFFECT_HISTORY_UNVERIFIED")
        val absent = view(intent())
        blocked(recover(key, absent,
            Verifier().apply { failWithException = true }, proof(history = absent)),
            "EFFECT_HISTORY_UNVERIFIED")
    }

    @Test fun unrelatedOperationsDoNotHideDuplicateIdsOrCorruptOrdering() {
        val other = key.copy(operationId = "operation-two",
            kind = EffectKind.RESULT_PUBLICATION)
        val history = view(intent(other), intent(), started(), committed())
        assertIs<EffectRecoveryOutcome.ConfirmedCommitted>(
            recover(key, history, Verifier()))
        blocked(recover(key.copy(operationId = "missing"),
            history, Verifier()), "EFFECT_INTENT_MISSING")
    }

    @Test fun allEffectKindsHaveTypedIntentAndReceiptContracts() {
        for (kind in EffectKind.entries) {
            val id = key.copy(kind = kind)
            assertIs<EffectRecoveryOutcome.ConfirmedCommitted>(
                recover(id, view(intent(id), started(id),
                    committed(id)), Verifier()), kind.name)
        }
    }

    @Test fun impossibleIdentityAndMalformedTerminalReceiptFailClosed() {
        val invalid = key.copy(inputSha256 = "untrusted")
        blocked(recover(invalid, view(intent(invalid)), Verifier()),
            "EFFECT_INVALID_IDENTITY")
        val history = view(intent(), started(), EffectRecord.Committed(key, "bad"))
        blocked(recover(key, history, Verifier()),
            "EFFECT_INVALID_HISTORY")
    }
}

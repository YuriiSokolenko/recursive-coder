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
        var historyThrows = false
        var commitThrows = false
        var notStartedThrows = false
        var lastAuthenticatedGeneration: Long? = null
        var lastIssuedGeneration: Long? = null
        override fun authenticateCompleteJournal(
            view: EffectJournalView, expected: EffectIdentity,
            currentFenceGeneration: Long
        ): Boolean {
            lastAuthenticatedGeneration = currentFenceGeneration
            lastIssuedGeneration = expected.fenceGeneration
            if (historyThrows) error("unavailable")
            // Test-only stand-in: production MUST verify the full persisted
            // ledger, its exact effect projection and both fence generations.
            return historyValid && view.entries.all { it.hostMacSha256 == "d".repeat(64) }
        }
        override fun independentlyVerifyCommitted(
            intent: EffectRecord.Intent, started: EffectRecord.Started,
            committed: EffectRecord.Committed
        ): Boolean {
            if (commitThrows) error("unavailable")
            return committedVerified
        }
        override fun independentlyVerifyNotStarted(
            intent: EffectRecord.Intent, proof: EffectNotStartedEvidence
        ): Boolean {
            if (notStartedThrows) error("unavailable")
            return absentVerified
        }
    }

    private fun recover(
        id: EffectIdentity, history: EffectJournalView,
        verifier: HostEffectEvidenceVerifier, proof: EffectNotStartedEvidence? = null,
        currentGeneration: Long = id.fenceGeneration
    ): EffectRecoveryOutcome =
        EffectReconciliationPolicy.reconcile(
            id, currentGeneration, history, verifier, proof)

    private fun blocked(result: EffectRecoveryOutcome, code: String) {
        assertEquals(EffectRecoveryOutcome.Quarantined(code), result)
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
        // The issuing fence is in the FUTURE relative to the recovery lease.
        blocked(recover(key, view(intent(), started(), committed()), Verifier(),
            currentGeneration = 2L), "EFFECT_STALE_GENERATION")
        blocked(recover(key, view(intent(), started(), committed()), Verifier(),
            currentGeneration = 0L), "EFFECT_STALE_GENERATION")
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
            "EFFECT_INTENT_MISSING")
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
            Verifier().apply { historyThrows = true }), "EFFECT_HISTORY_UNVERIFIED")
        val absent = view(intent())
        blocked(recover(key, absent,
            Verifier().apply { historyThrows = true }, proof(history = absent)),
            "EFFECT_HISTORY_UNVERIFIED")
        blocked(recover(key, history,
            Verifier().apply { commitThrows = true }), "EFFECT_COMMIT_UNVERIFIED")
        blocked(recover(key, absent,
            Verifier().apply { notStartedThrows = true }, proof(history = absent)),
            "EFFECT_NOT_STARTED_UNPROVEN")
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

    @Test fun crashTailEffectsCanBeClassifiedUnderLaterFenceGeneration() {
        // The operation is issued under fence 3; generation 4 is the trusted
        // new recovery lease. The verifier gets BOTH generations to check.
        val historical = view(intent(), started(), committed())
        val verifier = Verifier()
        assertIs<EffectRecoveryOutcome.ConfirmedCommitted>(
            recover(key, historical, verifier, currentGeneration = 4L))
        assertEquals(3L, verifier.lastIssuedGeneration)
        assertEquals(4L, verifier.lastAuthenticatedGeneration)

        val intentOnly = view(intent())
        assertIs<EffectRecoveryOutcome.ProvenNotStarted>(
            recover(key, intentOnly, Verifier(), proof(history = intentOnly),
                currentGeneration = 5L))
        // Even a valid historical record must not bypass an unavailable
        // historical fence / current lease verification.
        blocked(recover(key, historical,
            Verifier().apply { historyValid = false }, currentGeneration = 4L),
            "EFFECT_HISTORY_UNVERIFIED")
    }

    @Test fun projectionAllowsUnrelatedGapsOnlyWithAuthoritativeVerification() {
        val original = view(intent(), started(), committed())
        // Records at 1, 9, 12 are legal if the full authenticated session
        // ledger contains unrelated non-effect events at the skipped sequences.
        val gapped = original.copy(lastSequence = 15L,
            entries = original.entries.mapIndexed { index, entry ->
                entry.copy(sequence = listOf(1L, 9L, 12L)[index])
            })
        assertIs<EffectRecoveryOutcome.ConfirmedCommitted>(
            recover(key, gapped, Verifier()))
        blocked(recover(key, gapped, Verifier().apply { historyValid = false }),
            "EFFECT_HISTORY_UNVERIFIED")
    }

    @Test fun structuralUpperBoundsAndProofHashesAreValidated() {
        val history = view(intent(), started(), committed())
        blocked(recover(key, history.copy(lastSequence = 0L), Verifier()),
            "EFFECT_INVALID_HISTORY")
        blocked(recover(key, history.copy(lastSequence = 2L), Verifier()),
            "EFFECT_INVALID_HISTORY")
        val intentOnly = view(intent())
        blocked(recover(key, intentOnly, Verifier(),
            proof(history = intentOnly).copy(observedStateSha256 = "wrong")),
            "EFFECT_NONSTART_PROOF_MISMATCH")
        blocked(recover(key, intentOnly, Verifier(),
            proof(history = intentOnly).copy(hostMacSha256 = "wrong")),
            "EFFECT_NONSTART_PROOF_MISMATCH")
    }

    @Test fun longAuthenticatedProjectionsDoNotHitArtificialTenThousandEntryCliff() {
        val otherRecords = (1..10_001).map { index ->
            intent(key.copy(operationId = "other-${index}"))
        }
        val records = (otherRecords + listOf(intent(), started(), committed())).toTypedArray()
        assertIs<EffectRecoveryOutcome.ConfirmedCommitted>(
            recover(key, view(*records), Verifier()))
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

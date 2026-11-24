package ai.yuriilab.recursivecoder

import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The caller must be a trusted host. This is a checkpoint handoff, NOT
 * permission to start or replay execution from the resulting accepted state.
 */
@RequiresOptIn(
    message = "Trusted host only (#104/#105); durable handoff is NOT runtime execution authority.",
    level = RequiresOptIn.Level.ERROR
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class TrustedPreExecutionHandoff

/**
 * Opt-in #104 building block for a host that already durably published a
 * CREATED checkpoint. No model/tool/child/receipt callbacks run here.
 *
 * Exact order: admit C1 -> re-read admitted C1 -> reopen its bound ledger ->
 * append+force the single created-to-accepted event -> publish C2 on the SAME
 * open writer -> close writer -> retire lease. No uncertain tail is retried.
 *
 * The trusted host must not schedule execution while this function is running
 * or afterwards until #105 supplies authenticated runtime/receipt fencing.
 */
@TrustedPreExecutionHandoff
object DurableCreatedAcceptedHandoff {
    /**
     * Cheap, non-authoritative checks prevent deterministic frame errors from
     * consuming a fence generation. The open writer still validates the full
     * frame, actor, chain, metadata and physical ledger under the current lease.
     */
    private fun validProposal(source: SessionCheckpoint, event: JsonObject): Boolean {
        if (source.journalSequence == Int.MAX_VALUE) return false
        val next = source.journalSequence + 1
        fun text(field: String) = (event[field] as? JsonPrimitive)?.content
        return PreExecutionPhasePolicy.step(event, source.phase) ==
                SessionPhaseVocabulary.acceptedWire &&
            (event["sequence"] as? JsonPrimitive)?.intOrNull == next &&
            text("sessionId") == source.sessionId &&
            text("traceId") == source.traceId &&
            text("eventId") == SessionEventJournal.eventId(source.sessionId, next) &&
            text("causationId") ==
                SessionEventJournal.eventId(source.sessionId, source.journalSequence) &&
            FencedGraphMetadataPolicy.permittedFencedGraphMetadata(
                event, source.binding.graphId)
    }

    fun publish(
        checkpointDirectory: Path,
        context: CheckpointHostContext,
        ledgerPath: Path,
        attestation: ResumeHostAttestation,
        created: VerifiedSessionCheckpoint,
        acceptedTransition: JsonObject
    ): VerifiedSessionCheckpoint {
        // A caller-provided VerifiedSessionCheckpoint is not authenticated.
        // This filter is merely an early refusal, never publication authority.
        val proposal = created.snapshot
        if (created.reconciliationRequired ||
            proposal.phase != SessionPhaseVocabulary.createdWire ||
            !PreExecutionSnapshotPolicy.safe(proposal) ||
            !validProposal(proposal, acceptedTransition))
            throw SessionResumeUnconfirmed(ResumeDenialCodes.RESUME_UNSAFE_BOUNDARY)

        return SessionResumeAdmission.admit(
            checkpointDirectory, context, ledgerPath, attestation
        ).use { lease ->
            // Full immutable C1 identity, not just a caller-supplied SHA label.
            // The source for C2 MUST be the disk-authenticated snapshot pinned
            // to this lease, not the potentially hand-built caller object.
            val admitted = SessionCheckpointStore.admittedCheckpointForReopen(
                lease, checkpointDirectory, context)
            if (admitted != created)
                throw SessionResumeUnconfirmed(ResumeDenialCodes.RESUME_CHECKPOINT_CHANGED)
            val source = admitted.snapshot

            DurableSessionEventLedger.reopenForAppend(
                ledgerPath, context.workspaceRoot, checkpointDirectory, context, lease
            ).use { writer ->
                writer.appendFenced(acceptedTransition)
                val accepted = writer.withCommittedCheckpointAnchor { anchor ->
                    source.copy(
                        sequence = Math.addExact(source.sequence, 1L),
                        previousSha256 = admitted.sha256,
                        phase = SessionPhaseVocabulary.acceptedWire,
                        // This pre-execution accepted edge is an admitted,
                        // unstarted task, not a running task (issue #105).
                        taskState = "ready",
                        journalSequence = anchor.lastSequence,
                        journalHeadSha256 = anchor.headSha256,
                        journalFileSha256 = anchor.fileSha256,
                        lastTrustedJournalOffset = anchor.lastTrustedOffset
                    )
                }
                // A successful durable save can still be followed by a close
                // failure. Any exception requires read-only head reconciliation;
                // never infer that C2 is absent and blindly retry the handoff.
                SessionCheckpointStore.saveFromOpenLedgerFenced(
                    lease, checkpointDirectory, context, writer, accepted, admitted.sha256
                )
            }
        }
    }
}

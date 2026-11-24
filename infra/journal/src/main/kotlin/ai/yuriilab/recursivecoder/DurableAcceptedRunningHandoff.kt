package ai.yuriilab.recursivecoder

import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Host-only, effect-free transition from an authenticated C2 accepted prefix
 * to durable C3 running. C3 is a non-resumable marker: there is no execution
 * grant, model invocation, tool replay, receipt or terminal publication here.
 *
 * Order: preflight C2 -> admit generation N+1 -> reauthenticate the exact C2 ->
 * reopen checkpoint-bound writer -> fenced append+fsync -> fenced C3+fsync ->
 * close writer -> close OS lease. Any uncertain outcome requires read-only
 * reconciliation, never a blind retry.
 */
@TrustedRunningBoundaryApi
internal object DurableAcceptedRunningHandoff {
    private fun validProposal(source: SessionCheckpoint, event: JsonObject): Boolean {
        if (source.journalSequence == Int.MAX_VALUE) return false
        val next = source.journalSequence + 1
        fun text(name: String) = (event[name] as? JsonPrimitive)?.content
        return TrustedRunningBoundaryPolicy.step(event, source.phase) ==
            SessionPhaseVocabulary.runningWire &&
            (event[SessionEventFields.SEQUENCE] as? JsonPrimitive)?.intOrNull == next &&
            text(SessionEnvelopeFields.SESSION_ID) == source.sessionId &&
            text(SessionEnvelopeFields.TRACE_ID) == source.traceId &&
            text(SessionEventFields.EVENT_ID) == SessionEventJournal.eventId(source.sessionId, next) &&
            text(SessionEventFields.CAUSATION_ID) ==
                SessionEventJournal.eventId(source.sessionId, source.journalSequence) &&
            FencedGraphMetadataPolicy.permittedFencedGraphMetadata(
                event, source.binding.graphId)
    }

    fun publish(
        directory: Path,
        context: CheckpointHostContext,
        ledgerPath: Path,
        attestation: ResumeHostAttestation,
        accepted: VerifiedSessionCheckpoint,
        runningTransition: JsonObject
    ): VerifiedSessionCheckpoint {
        // A caller-provided checkpoint (even with a matching SHA label) has
        // no authority. This only avoids minting a fence for a known bad frame.
        val proposal = accepted.snapshot
        if (accepted.reconciliationRequired ||
            proposal.phase != SessionPhaseVocabulary.acceptedWire ||
            proposal.taskState != "ready" ||
            !PreExecutionSnapshotPolicy.safe(proposal) ||
            proposal.effectJournalHead != null ||
            proposal.effectReconciliation != null ||
            !validProposal(proposal, runningTransition))
            throw SessionResumeUnconfirmed("RESUME_UNSAFE_BOUNDARY")

        return SessionResumeAdmission.admit(
            directory, context, ledgerPath, attestation
        ).use { lease ->
            val admitted = SessionCheckpointStore.admittedCheckpointForReopen(
                lease, directory, context)
            if (admitted != accepted)
                throw SessionResumeUnconfirmed("RESUME_CHECKPOINT_CHANGED")
            val source = admitted.snapshot
            DurableSessionEventLedger.reopenForAppend(
                ledgerPath, context.workspaceRoot, directory, context, lease
            ).use { writer ->
                writer.appendTrustedAcceptedRunning(runningTransition)
                val running = writer.withCommittedCheckpointAnchor { anchor ->
                    source.copy(
                        sequence = Math.addExact(source.sequence, 1L),
                        previousSha256 = admitted.sha256,
                        phase = SessionPhaseVocabulary.runningWire,
                        // No task attempt, agent or child may be started here.
                        taskState = "ready",
                        journalSequence = anchor.lastSequence,
                        journalHeadSha256 = anchor.headSha256,
                        journalFileSha256 = anchor.fileSha256,
                        lastTrustedJournalOffset = anchor.lastTrustedOffset
                    )
                }
                SessionCheckpointStore.saveFromOpenLedgerFenced(
                    lease, directory, context, writer, running, admitted.sha256)
            }
        }
    }
}

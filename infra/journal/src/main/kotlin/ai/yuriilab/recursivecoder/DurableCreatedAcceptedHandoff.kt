package ai.yuriilab.recursivecoder

import java.nio.file.Path
import kotlinx.serialization.json.JsonObject

/**
 * The caller must be a trusted host. This is a checkpoint handoff, NOT
 * permission to start or replay execution from the resulting accepted state.
 */
@RequiresOptIn(
    message = "Only the trusted host may publish the durable pre-execution handoff.",
    level = RequiresOptIn.Level.ERROR
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class TrustedPreExecutionHandoff

/**
 * Opt-in #104 building block for a host that already durably published a
 * CREATED checkpoint. No model/tool/child/receipt callbacks run here.
 *
 * Exact order: admit C1 -> reopen the C1-bound ledger -> append+force the
 * single created-to-accepted event -> publish C2 on the SAME open writer ->
 * close writer -> retire lease. A failure never retries an uncertain tail.
 *
 * The trusted host must not schedule execution while this function is running
 * or afterwards until #105 supplies authenticated runtime/receipt fencing.
 */
@TrustedPreExecutionHandoff
object DurableCreatedAcceptedHandoff {
    fun publish(
        checkpointDirectory: Path,
        context: CheckpointHostContext,
        ledgerPath: Path,
        attestation: ResumeHostAttestation,
        created: VerifiedSessionCheckpoint,
        acceptedTransition: JsonObject
    ): VerifiedSessionCheckpoint {
        val source = created.snapshot
        // Reject a malformed proposal before minting a new fence generation.
        if (created.reconciliationRequired ||
            source.phase != SessionPhaseVocabulary.createdWire ||
            !PreExecutionSnapshotPolicy.safe(source) ||
            PreExecutionPhasePolicy.step(acceptedTransition, source.phase) !=
                SessionPhaseVocabulary.acceptedWire)
            throw SessionResumeUnconfirmed("RESUME_UNSAFE_BOUNDARY")

        return SessionResumeAdmission.admit(
            checkpointDirectory, context, ledgerPath, attestation
        ).use { lease ->
            // The caller's expected C1 must be the immutable C1 actually
            // admitted under this lease, not an earlier valid snapshot.
            if (lease.admittedCheckpointSha256 != created.sha256)
                throw SessionResumeUnconfirmed("RESUME_CHECKPOINT_CHANGED")

            DurableSessionEventLedger.reopenForAppend(
                ledgerPath, context.workspaceRoot, checkpointDirectory, context, lease
            ).use { writer ->
                writer.appendFenced(acceptedTransition)
                val accepted = writer.withCommittedCheckpointAnchor { anchor ->
                    source.copy(
                        sequence = Math.addExact(source.sequence, 1L),
                        previousSha256 = created.sha256,
                        phase = SessionPhaseVocabulary.acceptedWire,
                        taskState = "ready",
                        journalSequence = anchor.lastSequence,
                        journalHeadSha256 = anchor.headSha256,
                        journalFileSha256 = anchor.fileSha256,
                        lastTrustedJournalOffset = anchor.lastTrustedOffset
                    )
                }
                SessionCheckpointStore.saveFromOpenLedgerFenced(
                    lease, checkpointDirectory, context, writer, accepted, created.sha256
                )
            }
        }
    }
}

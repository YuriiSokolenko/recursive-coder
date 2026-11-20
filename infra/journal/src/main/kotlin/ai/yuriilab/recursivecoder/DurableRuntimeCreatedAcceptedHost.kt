package ai.yuriilab.recursivecoder

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlinx.serialization.json.*

/**
 * Opt-in, trusted-host adapter for the real DefaultRunSessionUseCase.
 *
 * The host supplies CURRENT independent Git/policy/authority facts. It must
 * create a durable C1 with authenticated ledger evidence before calling this
 * adapter. Neither a request nor a checkpoint proves its own authority.
 * On any failure, including an apparent post-write failure, operator-only
 * read-only reconciliation is required; no automatic C1 -> C2 retry.
 *
 * No model, workspace tool, child or runtime event sink is used. Publication
 * stops at C2 and never grants the caller permission to enter RUNNING.
 */
@TrustedPreExecutionHandoff
@OptIn(TrustedSessionPreExecutionApi::class)
class DurableRuntimeCreatedAcceptedHost(
    private val checkpointDirectory: Path,
    private val ledgerPath: Path,
    private val currentContext: () -> CheckpointHostContext,
    private val currentAttestation: () -> ResumeHostAttestation
) : TrustedSessionPreExecutionHost {
    private fun deny(): Nothing = throw SessionResumeUnconfirmed("RESUME_CONTEXT_CHANGED")

    private fun checkBeforePublication(
        cancellation: Cancellation,
        deadline: Instant,
        clock: Clock
    ) {
        when (SessionBudgetPolicy.interruption(
            cancellation.signals(), clock.instant(), deadline)) {
            SessionEndReason.TIMEOUT -> throw Stopped(true)
            SessionEndReason.CANCELLATION -> throw Stopped(false)
            else -> Unit
        }
    }

    private fun matchesRequest(request: SessionRequest, context: CheckpointHostContext) {
        // Untrusted paths can only be compared, never passed to the publisher.
        // Missing paths, invalid fields and IO failures have the same sanitized
        // code. Never attach the cause: it can contain an absolute host path.
        val matches = try {
            val suppliedBranch = request.workspace["branch"]
            // No requested branch: the independently checked host Git context
            // remains authoritative; do not invent a branch from the request.
            (suppliedBranch == null ||
                (suppliedBranch != JsonNull &&
                    context.gitBranch == "refs/heads/" + request.workspace.textAt("branch"))) &&
                request.output("checkpointDir").toAbsolutePath().normalize() ==
                    checkpointDirectory.toAbsolutePath().normalize() &&
                request.output("eventsPath").toAbsolutePath().normalize() ==
                    ledgerPath.toAbsolutePath().normalize() &&
                request.rootPath.toRealPath() == context.workspaceRoot.toRealPath() &&
                request.workspace.textAt("headSha") == context.gitHead &&
                request.policy.textAt("policySha256") == context.policySha256 &&
                request.raw.objectAt("resume").textAt("mode") == "fresh" &&
                request.raw.objectAt("resume").longAt("fenceGeneration") == 1L
        } catch (_: Exception) {
            false
        }
        if (!matches) deny()
    }

    override fun publishCreatedAccepted(
        request: SessionRequest,
        cancellation: Cancellation,
        deadline: Instant,
        clock: Clock
    ): TrustedSessionHandoffReceipt {
        checkBeforePublication(cancellation, deadline, clock)
        val context = currentContext()
        checkBeforePublication(cancellation, deadline, clock)
        val attestation = currentAttestation()
        checkBeforePublication(cancellation, deadline, clock)
        matchesRequest(request, context)
        checkBeforePublication(cancellation, deadline, clock)
        // Inspect physical C1; a forged request/checkpoint is never authority.
        val created = SessionCheckpointStore.inspect(checkpointDirectory, context, ledgerPath)
        checkBeforePublication(cancellation, deadline, clock)
        val c1 = created.snapshot
        val matchesIdentity = try {
            c1.sessionId == request.sessionId &&
                c1.traceId == request.traceId &&
                c1.taskId == request.task.textAt("taskId")
        } catch (_: Exception) {
            false
        }
        if (!matchesIdentity || c1.journalSequence <= 0 ||
            c1.journalSequence == Int.MAX_VALUE) deny()
        val transition = SessionEventJournal.frame(
            sessionId = c1.sessionId,
            traceId = c1.traceId,
            requestId = request.requestId,
            sequence = c1.journalSequence + 1,
            timestamp = clock.instant(),
            type = "session.transition",
            status = "completed",
            transition = SessionPhaseVocabulary.createdWire to
                SessionPhaseVocabulary.acceptedWire,
            extra = buildJsonObject { put("graphId", c1.binding.graphId) }
        )
        // Final cancellable gate. From this point through C2 publication and
        // lease retirement, cancellation/deadline cannot safely imply rollback.
        // Never check again and misreport a successful C2 as definitely absent.
        checkBeforePublication(cancellation, deadline, clock)
        // publish() re-reads the WHOLE disk-authenticated C1 under its new
        // OS lease, then appends the only legal edge and saves C2 on the
        // same writer. It closes writer before retiring the lease.
        val accepted = DurableCreatedAcceptedHandoff.publish(
            checkpointDirectory, context, ledgerPath, attestation, created, transition)
        return TrustedSessionHandoffReceipt(accepted.snapshot.sequence, accepted.sha256)
    }
}

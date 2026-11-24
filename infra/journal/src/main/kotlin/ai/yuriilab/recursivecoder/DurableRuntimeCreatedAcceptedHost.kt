package ai.yuriilab.recursivecoder

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlinx.serialization.json.*

/**
 * Shared read-only request/host checks for the two distinct opt-in adapters.
 * This helper never obtains a lease, appends a frame or publishes a checkpoint.
 * Its visibility does not grant either handoff capability.
 */
internal object RuntimeTrustedHandoffChecks {
    fun deny(): Nothing = throw SessionResumeUnconfirmed("RESUME_CONTEXT_CHANGED")

    fun checkBeforePublication(
        cancellation: Cancellation,
        deadline: Instant,
        clock: Clock
    ) {
        // A pre-existing thread interrupt closes interruptible FileChannels.
        // Observe, but NEVER clear, the caller's shutdown flag. This gate
        // refuses before fenced I/O, so clearing and restoring is unnecessary.
        // The host never binds cancellation to this thread.
        val interruptedBefore = Thread.currentThread().isInterrupted
        val stop = SessionInterruptionGate.stopped(
            cancellation.signals(), clock.instant(), deadline)
        val interruptedDuring = Thread.currentThread().isInterrupted
        if (stop != null) throw stop
        if (interruptedBefore || interruptedDuring) throw Stopped(false)
    }

    fun matchesRequest(request: SessionRequest, context: CheckpointHostContext,
        checkpointDirectory: Path, ledgerPath: Path) {
        // Untrusted paths can only be compared, never passed to the publisher.
        // Missing paths, invalid fields and IO failures have the same sanitized
        // code. Never attach the cause: it can contain an absolute host path.
        val matches = try {
            val suppliedBranch = request.workspace[WorkspaceFields.BRANCH]
            // No requested branch: the independently checked host Git context
            // remains authoritative; do not invent a branch from the request.
            (suppliedBranch == null ||
                (suppliedBranch != JsonNull &&
                    context.gitBranch == "refs/heads/" + request.workspace.textAt(WorkspaceFields.BRANCH))) &&
                request.output(SessionOutputFields.CHECKPOINT_DIR).toAbsolutePath().normalize() ==
                    checkpointDirectory.toAbsolutePath().normalize() &&
                request.output(SessionOutputFields.EVENTS_PATH).toAbsolutePath().normalize() ==
                    ledgerPath.toAbsolutePath().normalize() &&
                request.rootPath.toRealPath() == context.workspaceRoot.toRealPath() &&
                request.workspace.textAt(WorkspaceFields.HEAD_SHA) == context.gitHead &&
                request.policy.textAt(ToolPolicyFields.POLICY_SHA256) == context.policySha256 &&
                request.raw.objectAt(SessionRequestFields.RESUME).textAt(SessionResumeFields.MODE) == SessionResumeModes.FRESH &&
                request.raw.objectAt(SessionRequestFields.RESUME).longAt(SessionResumeFields.FENCE_GENERATION) == 1L
        } catch (_: Exception) {
            false
        }
        if (!matches) deny()
    }
}

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
    override fun publishCreatedAccepted(
        request: SessionRequest,
        cancellation: Cancellation,
        deadline: Instant,
        clock: Clock
    ): TrustedSessionHandoffReceipt {
        RuntimeTrustedHandoffChecks.checkBeforePublication(cancellation, deadline, clock)
        val context = currentContext()
        RuntimeTrustedHandoffChecks.checkBeforePublication(cancellation, deadline, clock)
        val attestation = currentAttestation()
        RuntimeTrustedHandoffChecks.checkBeforePublication(cancellation, deadline, clock)
        RuntimeTrustedHandoffChecks.matchesRequest(
            request, context, checkpointDirectory, ledgerPath)
        RuntimeTrustedHandoffChecks.checkBeforePublication(cancellation, deadline, clock)
        // Inspect physical C1; a forged request/checkpoint is never authority.
        val created = SessionCheckpointStore.inspect(checkpointDirectory, context, ledgerPath)
        RuntimeTrustedHandoffChecks.checkBeforePublication(cancellation, deadline, clock)
        val c1 = created.snapshot
        val matchesIdentity = try {
            c1.sessionId == request.sessionId &&
                c1.traceId == request.traceId &&
                c1.taskId == request.task.textAt("taskId")
        } catch (_: Exception) {
            false
        }
        if (!matchesIdentity || c1.journalSequence <= 0 ||
            c1.journalSequence == Int.MAX_VALUE) RuntimeTrustedHandoffChecks.deny()
        val transition = SessionEventJournal.frame(
            sessionId = c1.sessionId,
            traceId = c1.traceId,
            requestId = request.requestId,
            sequence = c1.journalSequence + 1,
            timestamp = clock.instant(),
            type = SessionEventKinds.SESSION_TRANSITION,
            status = "completed",
            transition = SessionPhaseVocabulary.createdWire to
                SessionPhaseVocabulary.acceptedWire,
            extra = buildJsonObject { put(StageEventMetadataFields.GRAPH_ID, c1.binding.graphId) }
        )
        // Last cooperative gate. A later Cancellation.cancel()/expire()
        // only updates signals, never interrupts the caller's FileChannels.
        // External Thread.interrupt(), IO failures and process death still
        // require read-only reconciliation if publication becomes uncertain.
        RuntimeTrustedHandoffChecks.checkBeforePublication(cancellation, deadline, clock)
        // publish() re-reads the WHOLE disk-authenticated C1 under its new
        // OS lease, then appends the only legal edge and saves C2 on the
        // same writer. It closes writer before retiring the lease.
        val accepted = DurableCreatedAcceptedHandoff.publish(
            checkpointDirectory, context, ledgerPath, attestation, created, transition)
        return TrustedSessionHandoffReceipt(accepted.snapshot.sequence, accepted.sha256)
    }
}

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
@OptIn(TrustedSessionPreExecutionApi::class, TrustedRunningBoundaryApi::class)
class DurableRuntimeCreatedAcceptedHost(
    private val checkpointDirectory: Path,
    private val ledgerPath: Path,
    private val currentContext: () -> CheckpointHostContext,
    private val currentAttestation: () -> ResumeHostAttestation
) : TrustedSessionPreExecutionHost, TrustedSessionRunningHost {
    private fun deny(): Nothing = throw SessionResumeUnconfirmed("RESUME_CONTEXT_CHANGED")

    private fun checkBeforePublication(
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
        // Last cooperative gate. A later Cancellation.cancel()/expire()
        // only updates signals, never interrupts the caller's FileChannels.
        // External Thread.interrupt(), IO failures and process death still
        // require read-only reconciliation if publication becomes uncertain.
        checkBeforePublication(cancellation, deadline, clock)
        // publish() re-reads the WHOLE disk-authenticated C1 under its new
        // OS lease, then appends the only legal edge and saves C2 on the
        // same writer. It closes writer before retiring the lease.
        val accepted = DurableCreatedAcceptedHandoff.publish(
            checkpointDirectory, context, ledgerPath, attestation, created, transition)
        return TrustedSessionHandoffReceipt(accepted.snapshot.sequence, accepted.sha256)
    }
    /**
     * A separate opt-in step: C2 is already durable. This does not delegate to
     * model execution or turn the new C3 into general resume authority.
     */
    override fun publishAcceptedRunning(
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
        val accepted = SessionCheckpointStore.inspect(checkpointDirectory, context, ledgerPath)
        checkBeforePublication(cancellation, deadline, clock)
        val c2 = accepted.snapshot
        val matchesIdentity = try {
            c2.sessionId == request.sessionId &&
                c2.traceId == request.traceId &&
                c2.taskId == request.task.textAt("taskId")
        } catch (_: Exception) {
            false
        }
        if (!matchesIdentity || c2.journalSequence <= 0 ||
            c2.journalSequence == Int.MAX_VALUE) deny()
        val transition = SessionEventJournal.frame(
            sessionId = c2.sessionId,
            traceId = c2.traceId,
            requestId = request.requestId,
            sequence = c2.journalSequence + 1,
            timestamp = clock.instant(),
            type = "session.transition",
            status = "completed",
            transition = SessionPhaseVocabulary.acceptedWire to "running",
            extra = buildJsonObject { put("graphId", c2.binding.graphId) }
        )
        // Late cooperative cancellation never interrupts a partially
        // fsynced physical transition; any exception after this point is
        // uncertain and requires read-only operator reconciliation.
        checkBeforePublication(cancellation, deadline, clock)
        val running = DurableAcceptedRunningHandoff.publish(
            checkpointDirectory, context, ledgerPath, attestation, accepted, transition)
        return TrustedSessionHandoffReceipt(running.snapshot.sequence, running.sha256)
    }

}

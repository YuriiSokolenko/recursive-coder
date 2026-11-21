package ai.yuriilab.recursivecoder

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Separate, explicitly marked C2 -> C3 adapter. Possessing the
 * @TrustedPreExecutionHandoff host MUST NOT expose this API. Both construction
 * and direct method access require TrustedRunningBoundaryApi opt-in.
 *
 * The request remains a FRESH invocation identity (resume.mode=fresh,
 * fenceGeneration=1); the independently verified attestation, NOT request
 * metadata, controls the second generation. This returns only an informational
 * C3 receipt, not an execution, restart or effect capability.
 */
@TrustedRunningBoundaryApi
class DurableRuntimeAcceptedRunningHost(
    private val checkpointDirectory: Path,
    private val ledgerPath: Path,
    private val currentContext: () -> CheckpointHostContext,
    private val currentAttestation: () -> ResumeHostAttestation
) : TrustedSessionRunningHost {
    override fun publishAcceptedRunning(
        request: SessionRequest,
        cancellation: Cancellation,
        deadline: Instant,
        clock: Clock
    ): TrustedSessionRunningReceipt {
        RuntimeTrustedHandoffChecks.checkBeforePublication(cancellation, deadline, clock)
        val context = currentContext()
        RuntimeTrustedHandoffChecks.checkBeforePublication(cancellation, deadline, clock)
        val attestation = currentAttestation()
        RuntimeTrustedHandoffChecks.checkBeforePublication(cancellation, deadline, clock)
        RuntimeTrustedHandoffChecks.matchesRequest(
            request, context, checkpointDirectory, ledgerPath)
        RuntimeTrustedHandoffChecks.checkBeforePublication(cancellation, deadline, clock)
        val accepted = SessionCheckpointStore.inspect(checkpointDirectory, context, ledgerPath)
        RuntimeTrustedHandoffChecks.checkBeforePublication(cancellation, deadline, clock)
        val c2 = accepted.snapshot
        val matchesIdentity = try {
            c2.sessionId == request.sessionId &&
                c2.traceId == request.traceId &&
                c2.taskId == request.task.textAt("taskId")
        } catch (_: Exception) {
            false
        }
        if (!matchesIdentity || c2.journalSequence <= 0 ||
            c2.journalSequence == Int.MAX_VALUE)
            RuntimeTrustedHandoffChecks.deny()

        val transition = SessionEventJournal.frame(
            sessionId = c2.sessionId,
            traceId = c2.traceId,
            requestId = request.requestId,
            sequence = c2.journalSequence + 1,
            timestamp = clock.instant(),
            type = "session.transition",
            status = "completed",
            transition = SessionPhaseVocabulary.acceptedWire to
                SessionPhaseVocabulary.runningWire,
            extra = buildJsonObject { put("graphId", c2.binding.graphId) }
        )
        // Cooperative deadline/cancellation stop before an uncertain append.
        // A later physical I/O failure is never proof that C3 was absent.
        RuntimeTrustedHandoffChecks.checkBeforePublication(cancellation, deadline, clock)
        val running = DurableAcceptedRunningHandoff.publish(
            checkpointDirectory, context, ledgerPath, attestation, accepted, transition)
        return TrustedSessionRunningReceipt(running.snapshot.sequence, running.sha256)
    }
}

package ai.yuriilab.recursivecoder

import java.nio.file.Path
import java.time.Clock
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
    private val currentAttestation: () -> ResumeHostAttestation,
    private val clock: Clock = Clock.systemUTC()
) : TrustedSessionPreExecutionHost {
    private fun deny(): Nothing = throw SessionResumeUnconfirmed("RESUME_CONTEXT_CHANGED")

    private fun matchesRequest(request: SessionRequest, context: CheckpointHostContext) {
        // Both paths are host-selected. Untrusted output paths are merely
        // compared and can never redirect the durable publisher.
        if (request.output("checkpointDir").toAbsolutePath().normalize() !=
                checkpointDirectory.toAbsolutePath().normalize() ||
            request.output("eventsPath").toAbsolutePath().normalize() !=
                ledgerPath.toAbsolutePath().normalize() ||
            request.rootPath.toRealPath() != context.workspaceRoot.toRealPath() ||
            request.workspace.textAt("headSha") != context.gitHead ||
            request.policy.textAt("policySha256") != context.policySha256 ||
            (request.workspace["branch"] != null &&
                context.gitBranch != "refs/heads/" + request.workspace.textAt("branch")) ||
            request.raw.objectAt("resume").textAt("mode") != "fresh" ||
            request.raw.objectAt("resume").longAt("fenceGeneration") != 1L)
            deny()
    }

    override fun publishCreatedAccepted(request: SessionRequest): TrustedSessionHandoffReceipt {
        val context = currentContext()
        val attestation = currentAttestation()
        matchesRequest(request, context)
        // Real on-disk inspection; never construct VerifiedSessionCheckpoint
        // from SessionRequest or a caller-supplied hash/JSON object.
        val created = SessionCheckpointStore.inspect(checkpointDirectory, context, ledgerPath)
        val c1 = created.snapshot
        if (c1.sessionId != request.sessionId || c1.traceId != request.traceId ||
            c1.taskId != request.task.textAt("taskId")) deny()
        if (c1.journalSequence == Int.MAX_VALUE) deny()
        val next = c1.journalSequence + 1
        val transition = buildJsonObject {
            put("kind", "session-event")
            put("version", SessionProtocol.VERSION)
            put("sessionId", c1.sessionId)
            put("traceId", c1.traceId)
            put("eventId", SessionEventJournal.eventId(c1.sessionId, next))
            put("sequence", next)
            put("timestamp", clock.instant().toString())
            put("type", "session.transition")
            put("status", "completed")
            put("correlationId", request.requestId)
            put("causationId", if (c1.journalSequence == 0) JsonNull else
                JsonPrimitive(SessionEventJournal.eventId(c1.sessionId, c1.journalSequence)))
            put("actor", buildJsonObject {
                put("agentId", "agent-" + c1.sessionId)
                put("parentAgentId", JsonNull)
                put("childAgentId", JsonNull)
                put("toolExecutionId", JsonNull)
                put("childSessionId", JsonNull)
            })
            put("transition", buildJsonObject {
                put("from", SessionPhaseVocabulary.createdWire)
                put("to", SessionPhaseVocabulary.acceptedWire)
            })
            put("redaction", buildJsonObject {
                put("mode", "metadata-only")
                put("removedFields", buildJsonArray {
                    listOf("rawPrompt", "rawResponse", "rawToolOutput",
                        "credentialRef", "authorizationHeader").forEach { add(it) }
                })
            })
            put("metadata", buildJsonObject { put("graphId", c1.binding.graphId) })
        }
        // publish() re-reads the WHOLE disk-authenticated C1 under its new
        // OS lease, then appends the only legal edge and saves C2 on the
        // same writer. It closes writer before retiring the lease.
        val accepted = DurableCreatedAcceptedHandoff.publish(
            checkpointDirectory, context, ledgerPath, attestation, created, transition)
        return TrustedSessionHandoffReceipt(accepted.snapshot.sequence, accepted.sha256)
    }
}

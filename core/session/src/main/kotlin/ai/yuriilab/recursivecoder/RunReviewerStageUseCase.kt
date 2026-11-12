package ai.yuriilab.recursivecoder

import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ReviewerStageCommand(
    val graph: StageGraph,
    val hostAcceptedArtifactIds: Set<ArtifactId>,
    val stageRunId: StageRunId,
    val model: ModelConfig,
    val instruction: String,
    val deadline: Instant,
    val cancellation: Cancellation
)

sealed interface ReviewerStageOutcome {
    data class Accepted(val decision: ReviewerDecisionRecord, val usage: StageRoleUsage) :
        ReviewerStageOutcome
    data class Rejected(val decision: ReviewerDecisionRecord, val usage: StageRoleUsage) :
        ReviewerStageOutcome
    data class NeedsHuman(val decision: ReviewerDecisionRecord, val usage: StageRoleUsage) :
        ReviewerStageOutcome
    data class Denied(val code: String) : ReviewerStageOutcome
    data class Failed(val code: String) : ReviewerStageOutcome
    data class ReconciliationRequired(val code: String) : ReviewerStageOutcome
}

data class ReviewerStageResult(
    val graph: StageGraph?,
    val hostAcceptedArtifactIds: Set<ArtifactId>?,
    val outcome: ReviewerStageOutcome
)

/**
 * Read-only Reviewer: the trusted host verifies receipts and frozen bytes;
 * the distinct reviewer model only proposes a tightly constrained verdict.
 * No tool opener, write operation or model-supplied host approval is reachable.
 */
class RunReviewerStageUseCase(
    private val roles: StageRoleExecutor,
    private val host: ReviewerStageHost,
    private val blockingDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC()
) {
    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun escaped(text: String): String = text.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun stopped(command: ReviewerStageCommand): String? {
        val signals = command.cancellation.signals()
        return when {
            signals.expired || !Instant.now(clock).isBefore(command.deadline) -> "ROLE_TIMED_OUT"
            signals.requested -> "ROLE_CANCELLED"
            else -> null
        }
    }

    private fun decode(text: String, correlation: StageCorrelation,
        manifestSha: String): ReviewerDecisionRecord? {
        val root = try { Json.parseToJsonElement(text) as? JsonObject }
            catch (_: Exception) { null } ?: return null
        if (root.keys != setOf("verdict", "code", "scope", "evidence")) return null
        fun string(key: String): String? = (root[key] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        val verdict = when (string("verdict")) {
            "ACCEPT" -> ReviewerDisposition.ACCEPT
            "REJECT" -> ReviewerDisposition.REJECT
            "NEEDS_HUMAN" -> ReviewerDisposition.NEEDS_HUMAN
            else -> return null
        }
        val scope = when (string("scope")) {
            "NONE" -> ReviewerCorrectionScope.NONE
            "IMPLEMENTATION_ONLY" -> ReviewerCorrectionScope.IMPLEMENTATION_ONLY
            "FROZEN_INPUTS" -> ReviewerCorrectionScope.FROZEN_INPUTS
            else -> return null
        }
        val code = string("code") ?: return null
        if (code !in setOf("OK", "IMPLEMENTATION_DEFECT", "FROZEN_ARTIFACT_CHANGE",
                "EVIDENCE_MISSING", "CHECK_FAILED", "OTHER_REQUIRES_HUMAN")) return null
        if (verdict == ReviewerDisposition.ACCEPT &&
            (code != "OK" || scope != ReviewerCorrectionScope.NONE) ||
            verdict == ReviewerDisposition.REJECT &&
            (code != "IMPLEMENTATION_DEFECT" ||
                scope != ReviewerCorrectionScope.IMPLEMENTATION_ONLY) ||
            verdict == ReviewerDisposition.NEEDS_HUMAN &&
            (code == "OK" || scope == ReviewerCorrectionScope.IMPLEMENTATION_ONLY))
            return null
        val values = root["evidence"] as? JsonArray ?: return null
        if (values.isEmpty() || values.size > 32) return null
        val refs = values.map {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
                ?: return null
        }
        if (refs.toSet().size != refs.size) return null
        return ReviewerDecisionRecord(correlation, verdict, code, scope,
            refs.toSet(), manifestSha)
    }

    suspend fun execute(
        command: ReviewerStageCommand, roleEvents: StageRoleEventSink,
        decisionEvents: ReviewerDecisionSink
    ): ReviewerStageResult {
        val original = command.graph
        val accepted = command.hostAcceptedArtifactIds.toSet()
        val id = command.stageRunId
        fun denied(code: String) = ReviewerStageResult(original, accepted,
            ReviewerStageOutcome.Denied(code))
        val stage = original.stages.singleOrNull {
            it.correlation.stageRunId == id && it.kind == StageKind.REVIEW &&
                it.supersededBy == null && it.review == null
        } ?: return denied("UNSUPPORTED_REVIEW_STAGE")
        if (command.instruction.isBlank()) return denied("EMPTY_STAGE_INSTRUCTION")
        val ready = when (stage.state) {
            StageState.CREATED -> {
                if (StageGraphPolicy.authorizeStageTransition(
                        original, id, StageState.READY) != StageGateDecision.Permit)
                    return denied("STAGE_NOT_READY")
                original.copy(stages = original.stages.map {
                    if (it.correlation.stageRunId == id)
                        it.copy(state = StageState.READY) else it
                })
            }
            StageState.READY -> original
            else -> return denied("STAGE_NOT_READY")
        }
        val gate = StageGraphPolicy.admitStage(ready, id, accepted)
        if (gate is StageGateDecision.Deny) return denied(gate.code)
        stopped(command)?.let { return denied(it) }
        val lease = try {
            withContext(NonCancellable + blockingDispatcher) {
                host.beginStage(original, id, accepted)
            }
        } catch (_: Exception) {
            return ReviewerStageResult(null, null,
                ReviewerStageOutcome.ReconciliationRequired("REVIEW_BEGIN_UNCONFIRMED"))
        } ?: return denied("REVIEW_ADMISSION_REVOKED")
        val running = lease.graph

        suspend fun abort(code: String): ReviewerStageResult {
            val terminal = if (code == "ROLE_TIMED_OUT" || code == "ROLE_CANCELLED")
                StageState.CANCELLED else StageState.FAILED
            val snapshot = try {
                withContext(NonCancellable + blockingDispatcher) {
                    host.abortStage(lease, terminal)
                }
            } catch (_: Exception) { null }
            return if (snapshot == null) ReviewerStageResult(null, null,
                ReviewerStageOutcome.ReconciliationRequired("REVIEW_ABORT_UNCONFIRMED"))
            else ReviewerStageResult(snapshot.graph, snapshot.acceptedIds,
                ReviewerStageOutcome.Failed(code))
        }
        suspend fun checkpoint(): String? {
            currentCoroutineContext().ensureActive()
            stopped(command)?.let { return it }
            return if (withContext(blockingDispatcher) { host.current(lease) })
                null else "STALE_REVIEW_LEASE"
        }
        try {
            if (lease.stageRunId != id || lease.acceptedIds != accepted ||
                !ToolAuthorizationPolicy.validHostIdentifier(lease.generation) ||
                running != ready.copy(stages = ready.stages.map {
                    if (it.correlation.stageRunId == id)
                        it.copy(state = StageState.RUNNING) else it
                }) || !host.current(lease)) return abort("INVALID_REVIEW_LEASE")
            checkpoint()?.let { return abort(it) }
            val evidence = lease.handoff.developerResult
            val manifest = evidence.manifest
            val developer = running.stages.singleOrNull {
                it.correlation.stageRunId == manifest.producerStageRunId &&
                    it.kind == StageKind.DEVELOP && it.state == StageState.ACCEPTED &&
                    it.supersededBy == null
            } ?: return abort("INVALID_DEVELOPER_HANDOFF")
            if (running.artifacts.singleOrNull { it.id == manifest.id } != manifest ||
                manifest.kind != StageArtifactKind.IMPLEMENTATION ||
                manifest.approval != ArtifactApproval.ACCEPTED ||
                manifest.freeze != ArtifactFreeze.FROZEN ||
                manifest.id !in accepted || manifest.id !in stage.inputArtifacts ||
                developer.correlation.stageRunId !in stage.dependencies ||
                developer.agentId == stage.agentId ||
                evidence.validation.correlation != developer.correlation ||
                evidence.validation.actor != developer.agentId ||
                !evidence.validation.successful || evidence.writeToolId.isBlank() ||
                evidence.changedFiles.isEmpty() ||
                evidence.changedFiles.size > 7 ||
                evidence.changedFiles.map { it.path }.toSet().size != evidence.changedFiles.size)
                return abort("INVALID_DEVELOPER_HANDOFF")
            val checks = evidence.validation.requiredChecks
            if (checks.map { it.checkId }.toSet().size != checks.size ||
                checks.none { it.checkId == "git_diff_check" } ||
                checks.none { it.checkId != "git_diff_check" } ||
                checks.any { !it.passed || it.toolId.isNullOrBlank() ||
                    it.baselineHead.isNullOrBlank() } ||
                !host.verifyDeveloperEvidence(lease))
                return abort("UNVERIFIED_DEVELOPER_EVIDENCE")

            val requiredInputs = running.artifacts.filter {
                it.id in stage.inputArtifacts && it.id != manifest.id
            }.sortedBy { it.kind.name }
            val verifiedPaths = (requiredInputs.map { it.declaredPath to it.sha256 } +
                (manifest.declaredPath to manifest.sha256) +
                evidence.changedFiles.map { it.path to it.afterSha256 })
            suspend fun intact(): Boolean = withContext(blockingDispatcher) {
                host.verifyDeveloperEvidence(lease) && verifiedPaths.all { (path, digest) ->
                    host.readBytes(path)?.let { sha(it) == digest } == true
                }
            }
            if (!intact()) return abort("FROZEN_INPUT_OR_SOURCE_CHANGED")
            val prompt = buildString {
                append(command.instruction)
                append("\n\nYou are an independent, read-only Reviewer. Return ONLY JSON:")
                append(" {\"verdict\":\"ACCEPT|REJECT|NEEDS_HUMAN\",")
                append("\"code\":\"OK|IMPLEMENTATION_DEFECT|FROZEN_ARTIFACT_CHANGE|")
                append("EVIDENCE_MISSING|CHECK_FAILED|OTHER_REQUIRES_HUMAN\",")
                append("\"scope\":\"NONE|IMPLEMENTATION_ONLY|FROZEN_INPUTS\",")
                append("\"evidence\":[\"check:git_diff_check\"]}.")
                append(" ACCEPT requires ALL trusted check and write evidence IDs.")
                append(" REJECT is ONLY for implementation-only corrections.")
                append(" Frozen specification/test revisions require NEEDS_HUMAN.")
                for (artifact in requiredInputs + manifest) {
                    val bytes = host.readBytes(artifact.declaredPath)
                        ?: return abort("ARTIFACT_MISSING")
                    val body = bytes.toString(Charsets.UTF_8)
                    append("\n<artifact kind=\"").append(artifact.kind.name)
                        .append("\" sha256=\"").append(artifact.sha256).append("\">\n")
                    append(escaped(body)).append("\n</artifact>")
                }
                for (change in evidence.changedFiles) {
                    val bytes = host.readBytes(change.path)
                        ?: return abort("CHANGED_FILE_MISSING")
                    append("\n<changed-file path=\"").append(escaped(change.path))
                        .append("\" before-sha256=\"")
                        .append(change.beforeSha256 ?: "-").append("\" after-sha256=\"")
                        .append(change.afterSha256).append("\">\n")
                    append(escaped(bytes.toString(Charsets.UTF_8)))
                    append("\n</changed-file>")
                }
                checks.forEach {
                    append("\n<verified-check id=\"").append(escaped(it.checkId))
                        .append("\" receipt=\"").append(escaped(requireNotNull(it.toolId)))
                        .append("\" />")
                }
                append("\n<verified-write id=\"").append(escaped(evidence.writeToolId))
                    .append("\" />")
                lease.handoff.history.takeLast(32).forEach {
                    append("\n<history stage=\"").append(escaped(it.correlation.stageRunId.value))
                        .append("\" role=\"").append(it.role.name).append("\" state=\"")
                        .append(it.state.name).append("\" />")
                }
            }
            if (prompt.toByteArray(Charsets.UTF_8).size > StageRoleLimits.MAX_INSTRUCTION_BYTES)
                return abort("REVIEW_INPUT_TOO_LARGE")
            checkpoint()?.let { return abort(it) }
            val proposal = roles.execute(StageRoleRequest(
                ready, id, stage.correlation.requestId, EngineeringRole.REVIEWER,
                accepted, command.model, prompt, command.deadline, command.cancellation,
                requestedTools = emptySet(), maxModelRequests = 1
            ), roleEvents)
            checkpoint()?.let { return abort(it) }
            if (proposal.correlation != stage.correlation ||
                proposal.role != EngineeringRole.REVIEWER || proposal.agentId != stage.agentId)
                return abort("REVIEW_RESULT_IDENTITY_MISMATCH")
            val text = when (val outcome = proposal.outcome) {
                is StageRoleOutcome.Proposed -> outcome.text
                is StageRoleOutcome.Denied -> return abort(outcome.code)
                is StageRoleOutcome.Failed -> return abort(outcome.code)
                StageRoleOutcome.Cancelled -> return abort("ROLE_CANCELLED")
                StageRoleOutcome.TimedOut -> return abort("ROLE_TIMED_OUT")
            }
            val usage = proposal.usage
            val inputTokens = usage.inputTokens
            val outputTokens = usage.outputTokens
            if (usage.modelRequests != 1 || inputTokens == null || outputTokens == null ||
                inputTokens < 0 || outputTokens < 0 ||
                inputTokens > running.budget.maxTokens ||
                outputTokens > command.model.maxOutputTokens ||
                outputTokens > running.budget.maxTokens - inputTokens)
                return abort("INVALID_REVIEW_USAGE")
            if (text.toByteArray(Charsets.UTF_8).size > StageRoleLimits.MAX_PROPOSAL_BYTES)
                return abort("INVALID_REVIEW_RESPONSE")
            val decision = decode(text, stage.correlation, manifest.sha256)
                ?: return abort("INVALID_REVIEW_RESPONSE")
            if (!intact()) return abort("FROZEN_INPUT_OR_SOURCE_CHANGED")
            checkpoint()?.let { return abort(it) }
            val review = StageReviewDecision(developer.correlation.stageRunId, manifest.id,
                manifest.sha256, developer.agentId ?: return abort("MISSING_DEVELOPER_ACTOR"),
                stage.agentId ?: return abort("MISSING_REVIEWER_ACTOR"),
                when (decision.verdict) {
                    ReviewerDisposition.ACCEPT -> ReviewVerdict.ACCEPT
                    ReviewerDisposition.REJECT -> ReviewVerdict.REQUEST_CHANGES
                    ReviewerDisposition.NEEDS_HUMAN -> ReviewVerdict.REJECT
                })
            val target = if (decision.verdict == ReviewerDisposition.ACCEPT)
                StageState.ACCEPTED else StageState.REJECTED
            val updated = running.copy(stages = running.stages.map {
                if (it.correlation.stageRunId == id)
                    it.copy(state = target, review = review) else it
            })
            val gateResult = ReviewerStageFinishPolicy.permits(lease, updated, accepted,
                decision, host.verifyDeveloperEvidence(lease))
            if (gateResult is StageGateDecision.Deny) return abort(gateResult.code)
            if (!intact()) return abort("FROZEN_INPUT_OR_SOURCE_CHANGED")
            checkpoint()?.let { return abort(it) }
            val committed = try {
                withContext(NonCancellable + blockingDispatcher) {
                    host.finishStage(lease, updated, accepted, decision)
                }
            } catch (_: Exception) {
                return ReviewerStageResult(null, null,
                    ReviewerStageOutcome.ReconciliationRequired("REVIEW_FINISH_UNCONFIRMED"))
            }
            if (!committed) return ReviewerStageResult(null, null,
                ReviewerStageOutcome.ReconciliationRequired("STALE_REVIEW_LEASE"))
            try { decisionEvents.emit(decision) }
            catch (_: Exception) { /* Host CAS remains authoritative. */ }
            val outcome = when (decision.verdict) {
                ReviewerDisposition.ACCEPT -> ReviewerStageOutcome.Accepted(decision, usage)
                ReviewerDisposition.REJECT -> ReviewerStageOutcome.Rejected(decision, usage)
                ReviewerDisposition.NEEDS_HUMAN -> ReviewerStageOutcome.NeedsHuman(decision, usage)
            }
            return ReviewerStageResult(updated, accepted, outcome)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                try { abort("ROLE_CANCELLED") }
                catch (cleanup: Exception) {
                    if (cleanup !== cancelled) cancelled.addSuppressed(cleanup)
                }
            }
            throw cancelled
        } catch (_: Exception) {
            return abort("REVIEW_STAGE_FAILED")
        }
    }
}

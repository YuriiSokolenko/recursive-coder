package ai.yuriilab.recursivecoder

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration

/** Exactly one separately invoked SDD, ATDD or TDD attempt. */
data class PreparationStageCommand(
    val graph: StageGraph,
    val hostAcceptedArtifactIds: Set<ArtifactId>,
    val stageRunId: StageRunId,
    val artifactId: ArtifactId,
    val outputPath: String,
    val instruction: String,
    val model: ModelConfig,
    val deadline: Instant,
    val cancellation: Cancellation
)

sealed interface PreparationStageOutcome {
    data class Accepted(
        val manifest: StageArtifactManifest,
        val validation: StageValidationEvidence,
        val usage: StageRoleUsage
    ) : PreparationStageOutcome
    data class Denied(val code: String) : PreparationStageOutcome
    data class Failed(val code: String) : PreparationStageOutcome
    data class AwaitingHuman(val manifest: StageArtifactManifest) : PreparationStageOutcome
    /** No graph/approval data is trustworthy: reload authoritative host state. */
    data class ReconciliationRequired(val code: String) : PreparationStageOutcome
}

data class PreparationStageResult(
    /** Null only when a commit/lease has uncertain authoritative state. */
    val graph: StageGraph?,
    val hostAcceptedArtifactIds: Set<ArtifactId>?,
    val outcome: PreparationStageOutcome
)

data class PreparationStageEvent(
    val correlation: StageCorrelation,
    val kind: StageKind,
    val state: StageState,
    val approval: ArtifactApproval? = null,
    val artifactId: ArtifactId? = null,
    val sha256: String? = null,
    val code: String? = null
)

fun interface PreparationStageEventSink {
    fun emit(event: PreparationStageEvent)
}

/**
 * No model-facing tools. Writes and checks go through the trusted stage port
 * only after a host-owned atomic begin. Every terminal state is committed
 * through the same lease; a failed CAS can never be reported as accepted.
 */
class RunPreparationStageUseCase(
    private val roles: StageRoleExecutor,
    private val tools: StageToolSessionOpener,
    private val host: PreparationStageHost,
    private val blockingDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC()
) {
    private val kinds = setOf(StageKind.SDD, StageKind.ATDD, StageKind.TDD)

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun utf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { null }

    private fun update(graph: StageGraph, id: StageRunId, state: StageState): StageGraph =
        graph.copy(stages = graph.stages.map {
            if (it.correlation.stageRunId == id) it.copy(state = state) else it
        })

    private fun transition(
        graph: StageGraph, id: StageRunId, state: StageState, accepted: Set<ArtifactId>
    ): StageGraph? =
        if (StageGraphPolicy.authorizeStageTransition(graph, id, state, accepted) ==
            StageGateDecision.Permit) update(graph, id, state) else null

    private fun stopped(command: PreparationStageCommand): String? {
        val signals = command.cancellation.signals()
        return when {
            signals.expired || !Instant.now(clock).isBefore(command.deadline) -> StageDenialCodes.ROLE_TIMED_OUT
            signals.requested -> StageDenialCodes.ROLE_CANCELLED
            else -> null
        }
    }

    private suspend fun verifyInputs(graph: StageGraph, stage: StageNode): HostReadVerification =
        verifyHostReads(graph.artifacts.filter { it.id in stage.inputArtifacts }.map { artifact ->
            artifact.declaredPath to { bytes: ByteArray? ->
                bytes != null && digest(bytes) == artifact.sha256
            }
        }) { path -> withContext(blockingDispatcher) { host.readBytes(path) } }

    private suspend fun live(lease: PreparationStageLease, snapshot: StageToolHostSnapshot): Boolean =
        try {
            withContext(blockingDispatcher) {
                host.current(lease) && lease.authority.current() == snapshot
            }
        } catch (failure: Exception) {
            ensureHostCallerActive(failure)
            false
        }

    private fun emit(
        events: PreparationStageEventSink, graph: StageGraph, id: StageRunId,
        manifest: StageArtifactManifest? = null, code: String? = null
    ) {
        // Audit delivery is best-effort in this in-process port. An event sink
        // must never turn a committed host success into a failure or strand a lease.
        try {
            val stage = graph.stages.first { it.correlation.stageRunId == id }
            events.emit(PreparationStageEvent(stage.correlation, stage.kind, stage.state,
                manifest?.approval, manifest?.id, manifest?.sha256, code))
        } catch (_: Exception) { /* Host commit is authoritative; event delivery is not. */ }
    }

    private suspend fun finish(
        lease: PreparationStageLease, graph: StageGraph, accepted: Set<ArtifactId>,
        outcome: PreparationStageOutcome, events: PreparationStageEventSink,
        manifest: StageArtifactManifest? = null, code: String? = null,
        pending: PreparationPendingApproval? = null
    ): PreparationStageResult {
        currentCoroutineContext().ensureActive()
        // A host CAS may already have committed when a coroutine is cancelled.
        // Never report an uncertain host state as a definite denial or publish
        // a stale snapshot; only a confirmed true is an acknowledged result.
        val committed = try {
            withContext(NonCancellable + blockingDispatcher) {
                host.finishStage(lease, graph, accepted, pending)
            }
        } catch (_: Exception) {
            return PreparationStageResult(null, null,
                PreparationStageOutcome.ReconciliationRequired(StageDenialCodes.STAGE_FINISH_UNCONFIRMED))
        }
        if (!committed)
            return PreparationStageResult(null, null,
                PreparationStageOutcome.ReconciliationRequired(StageDenialCodes.STALE_STAGE_LEASE))
        emit(events, graph, lease.stageRunId, manifest, code)
        return PreparationStageResult(graph, accepted, outcome)
    }

    private suspend fun abort(
        lease: PreparationStageLease, terminal: StageState, code: String,
        events: PreparationStageEventSink
    ): PreparationStageResult {
        val snapshot = try {
            withContext(NonCancellable + blockingDispatcher) { host.abortStage(lease, terminal) }
        } catch (_: Exception) { null }
            ?: return PreparationStageResult(null, null,
                PreparationStageOutcome.ReconciliationRequired(StageDenialCodes.STAGE_ABORT_UNCONFIRMED))
        emit(events, snapshot.graph, lease.stageRunId, code = code)
        return PreparationStageResult(snapshot.graph, snapshot.acceptedIds,
            PreparationStageOutcome.Failed(code))
    }

    suspend fun execute(
        command: PreparationStageCommand,
        roleEvents: StageRoleEventSink,
        events: PreparationStageEventSink
    ): PreparationStageResult {
        val original = command.graph
        val accepted = command.hostAcceptedArtifactIds.toSet()
        val id = command.stageRunId
        fun denied(code: String) = PreparationStageResult(original, accepted,
            PreparationStageOutcome.Denied(code))
        val stage = original.stages.firstOrNull { it.correlation.stageRunId == id }
            ?: return denied(RunPreparationStageUseCaseDiagnosticCodes.UNSUPPORTED_PREPARATION_STAGE)
        if (stage.kind !in kinds || stage.supersededBy != null)
            return denied(RunPreparationStageUseCaseDiagnosticCodes.UNSUPPORTED_PREPARATION_STAGE)
        val kind = stage.kind
        if (stage.outputArtifacts.isNotEmpty() ||
            original.artifacts.any { it.id == command.artifactId ||
                it.declaredPath == command.outputPath })
            return denied("DUPLICATE_ARTIFACT_ID_OR_PATH")
        if (ToolAuthorizationPolicy.authorizeWrite(WriteAuthorizationFacts(
                kind.role, WriteOperationKind.CREATE, command.outputPath
            )) != ToolAuthorizationDecision.Permit)
            return denied(ToolDenialCodes.ROLE_NOT_AUTHORIZED)
        if (command.instruction.isBlank()) return denied(StageDenialCodes.EMPTY_STAGE_INSTRUCTION)
        val ready = when (stage.state) {
            StageState.CREATED -> transition(original, id, StageState.READY, accepted)
                ?: return denied(StageDenialCodes.STAGE_NOT_READY)
            StageState.READY -> original
            else -> return denied(StageDenialCodes.STAGE_NOT_READY)
        }
        val admission = StageGraphPolicy.admitStage(ready, id, accepted)
        if (admission is StageGateDecision.Deny) return denied(admission.code)

        val inputs = ready.artifacts.filter { it.id in stage.inputArtifacts }
            .sortedBy { it.kind.name }
        val inputData = inputs.map { artifact ->
            artifact to captureHostCall {
                withContext(blockingDispatcher) { host.readBytes(artifact.declaredPath) }
            }
        }
        for ((artifact, read) in inputData) {
            if (read.isFailure) continue
            val bytes = read.getOrThrow() ?: return denied(StageDenialCodes.ARTIFACT_MISSING)
            if (digest(bytes) != artifact.sha256) return denied(StageDenialCodes.ARTIFACT_DIGEST_MISMATCH)
            if (utf8(bytes) == null) return denied(StageDenialCodes.ARTIFACT_ENCODING_INVALID)
        }
        if (inputData.any { it.second.isFailure }) return denied(StageDenialCodes.INPUT_READ_UNAVAILABLE)
        val instruction = buildString {
            append(command.instruction)
            for ((artifact, read) in inputData) {
                val text = requireNotNull(utf8(requireNotNull(read.getOrThrow())))
                append("\n\n<verified-input kind=\"").append(artifact.kind.name)
                    .append("\" sha256=\"").append(artifact.sha256).append("\">\n")
                append(text.replace("&", "&amp;").replace("<", "&lt;"))
                append("\n</verified-input>")
            }
        }
        if (instruction.toByteArray(Charsets.UTF_8).size > StageRoleLimits.MAX_INSTRUCTION_BYTES)
            return denied(StageDenialCodes.STAGE_INPUT_TOO_LARGE)
        stopped(command)?.let { return denied(it) }

        // The authoritative host must atomically claim this attempt before
        // the proposal engine can run. A second concurrent claimant fails.
        val lease = try {
            // The host may finish the CAS even as the caller is cancelled.
            // Always retrieve the issued lease so the attempt can be aborted.
            withContext(NonCancellable + blockingDispatcher) {
                host.beginStage(original, id, accepted)
            }
        } catch (_: Exception) {
            return PreparationStageResult(null, null,
                PreparationStageOutcome.ReconciliationRequired(StageDenialCodes.STAGE_BEGIN_UNCONFIRMED))
        } ?: return denied(StageDenialCodes.STAGE_ADMISSION_REVOKED)
        val running = lease.graph
        val activeStage = running.stages.firstOrNull { it.correlation.stageRunId == id }
        val validLease = try {
            activeStage?.state == StageState.RUNNING &&
                running == update(ready, id, StageState.RUNNING) &&
                lease.acceptedIds == accepted &&
                lease.stageRunId == id &&
                ToolAuthorizationPolicy.validHostIdentifier(lease.generation) &&
                withContext(NonCancellable + blockingDispatcher) { host.current(lease) }
        } catch (_: Exception) { false }
        if (!validLease)
            return withContext(NonCancellable) {
                abort(lease, StageState.FAILED, StageDenialCodes.INVALID_STAGE_LEASE, events)
            }
        emit(events, running, id)

        suspend fun terminal(code: String, state: StageState = StageState.FAILED,
                     rejected: Boolean = false): PreparationStageResult {
            val completed = transition(running, id, state, accepted)
                ?: return abort(lease, if (state == StageState.CANCELLED)
                    StageState.CANCELLED else StageState.FAILED,
                    code, events)
            return finish(lease, completed, accepted,
                if (rejected) PreparationStageOutcome.Denied(code)
                else PreparationStageOutcome.Failed(code), events, code = code)
        }
        suspend fun checkpoint(): String? {
            currentCoroutineContext().ensureActive()
            stopped(command)?.let { return it }
            val current = captureHostCall {
                withContext(blockingDispatcher) { host.current(lease) }
            }.getOrElse { return StageDenialCodes.STAGE_AUTHORITY_UNAVAILABLE }
            return if (current) null else StageDenialCodes.STALE_STAGE_LEASE
        }
        suspend fun halted(code: String): PreparationStageResult = terminal(code,
            if (code == StageDenialCodes.ROLE_CANCELLED || code == StageDenialCodes.ROLE_TIMED_OUT)
                StageState.CANCELLED else StageState.FAILED)

        try {
            checkpoint()?.let { return halted(it) }
            // Koog's proposal port admits only READY; the host lease is already
            // RUNNING, and every later physical effect rechecks live authority.
            val proposal = roles.execute(StageRoleRequest(
                ready, id, stage.correlation.requestId, kind.role, accepted,
                command.model, instruction, command.deadline, command.cancellation
            ), roleEvents)
            checkpoint()?.let { return halted(it) }
            if (proposal.correlation != stage.correlation ||
                proposal.role != kind.role || proposal.agentId != stage.agentId)
                return terminal(StageDenialCodes.ROLE_RESULT_IDENTITY_MISMATCH)
            val proposed = when (val outcome = proposal.outcome) {
                is StageRoleOutcome.Proposed -> outcome.text
                is StageRoleOutcome.Denied -> return terminal(outcome.code, rejected = true)
                is StageRoleOutcome.Failed -> return terminal(outcome.code)
                StageRoleOutcome.Cancelled -> return halted(StageDenialCodes.ROLE_CANCELLED)
                StageRoleOutcome.TimedOut -> return halted(StageDenialCodes.ROLE_TIMED_OUT)
            }
            if (proposed.isBlank() || proposed.toByteArray(Charsets.UTF_8).size >
                StageRoleLimits.MAX_PROPOSAL_BYTES)
                return terminal(ModelDenialCodes.MODEL_INVALID_RESPONSE)
            val authority = lease.authority
            val snapshot = captureHostCall {
                withContext(blockingDispatcher) { authority.current() }
            }.getOrElse { return terminal(StageDenialCodes.STAGE_AUTHORITY_UNAVAILABLE) }
                ?: return terminal(StageDenialCodes.STAGE_AUTHORITY_UNAVAILABLE)
            if (snapshot.graph != running || snapshot.stageRunId != id ||
                snapshot.requestId != stage.correlation.requestId ||
                snapshot.hostAcceptedArtifactIds != accepted ||
                !live(lease, snapshot))
                return terminal(StageDenialCodes.STAGE_AUTHORITY_MISMATCH)
            val checks = snapshot.requiredChecks
            if (StageCheckIds.GIT_DIFF_CHECK !in checks || checks.none { it != StageCheckIds.GIT_DIFF_CHECK } ||
                checks.any { it != StageCheckIds.GIT_DIFF_CHECK && it !in snapshot.checks })
                return terminal(StageDenialCodes.STAGE_CHECKS_REQUIRED)
            checkpoint()?.let { return halted(it) }
            val open = withContext(blockingDispatcher) { tools.open(authority) }
            if (open is StageToolOpenResult.Denied) return terminal(open.code)
            val session = (open as StageToolOpenResult.Granted).session
            var checkDenial: String? = null
            val validation = withStageSessionCleanup(
                close = { withContext(NonCancellable + blockingDispatcher) { session.close() } }
            ) {
                withContext(blockingDispatcher) {
                    checkDenial = checkpoint()
                    if (checkDenial != null) return@withContext null
                    val write = session.write(listOf(StageWrite.Create(command.outputPath, proposed)))
                    if (!write.permitted) {
                        checkDenial = write.deniedCode ?: StageDenialCodes.STAGE_WRITE_FAILED
                        return@withContext null
                    }
                    for (check in checks.sorted()) {
                        checkDenial = checkpoint() ?: if (!live(lease, snapshot))
                            StageDenialCodes.STALE_STAGE_AUTHORITY else null
                        if (checkDenial != null) return@withContext null
                        val evidence = session.runCheck(check)
                        if (!evidence.passed) {
                            checkDenial = evidence.deniedCode ?: StageDenialCodes.STAGE_CHECK_FAILED
                            return@withContext null
                        }
                    }
                    checkDenial = checkpoint() ?: if (!live(lease, snapshot))
                        StageDenialCodes.STALE_STAGE_AUTHORITY else null
                    if (checkDenial != null) return@withContext null
                    session.verify()
                }
            }
            checkpoint()?.let { return halted(it) }
            if (!live(lease, snapshot)) return terminal(StageDenialCodes.STALE_STAGE_AUTHORITY)
            if (validation == null) return terminal(checkDenial ?: StageDenialCodes.STAGE_TOOL_VALIDATION_FAILED)
            if (!validation.successful || validation.correlation != stage.correlation ||
                validation.actor != stage.agentId ||
                validation.requiredChecks.map { it.checkId }.toSet() != checks ||
                validation.requiredChecks.any { !it.passed })
                return terminal(validation.deniedCode ?: StageDenialCodes.STAGE_VALIDATION_FAILED)
            val outputRead = captureHostCall {
                withContext(blockingDispatcher) { host.readBytes(command.outputPath) }
            }
            val inputsVerified = verifyInputs(running, stage)
            val intended = proposed.toByteArray(Charsets.UTF_8)
            if (outputRead.isSuccess) {
                val output = outputRead.getOrThrow() ?: return terminal(StageDenialCodes.ARTIFACT_MISSING)
                if (!output.contentEquals(intended)) return terminal(StageDenialCodes.ARTIFACT_DIGEST_MISMATCH)
            }
            if (inputsVerified == HostReadVerification.CHANGED) return terminal("FROZEN_INPUT_CHANGED")
            val bytes = outputRead.getOrElse { return terminal(StageDenialCodes.OUTPUT_READ_UNAVAILABLE) }
                ?: return terminal(StageDenialCodes.ARTIFACT_MISSING)
            if (inputsVerified == HostReadVerification.UNAVAILABLE) return terminal(StageDenialCodes.INPUT_READ_UNAVAILABLE)
            val outputKind = kind.output ?: return terminal(StageDenialCodes.INVALID_STAGE_OUTPUT)
            val candidate = StageArtifactManifest(command.artifactId, outputKind,
                command.outputPath, digest(bytes), kind.role, id, 1L,
                ArtifactApproval.DRAFT, ArtifactFreeze.EDITABLE)
            if (!live(lease, snapshot)) return terminal(StageDenialCodes.STALE_STAGE_AUTHORITY)
            val decision = try {
                withContext(blockingDispatcher) {
                    val remaining = Duration.between(Instant.now(clock), command.deadline).toMillis()
                    if (remaining <= 0) return@withContext null
                    withTimeoutOrNull(remaining) { host.approve(candidate, validation, proposal.usage) }
                }
            } catch (failure: Exception) {
                ensureHostCallerActive(failure)
                stopped(command)?.let { return halted(it) }
                return terminal(StageDenialCodes.HOST_APPROVAL_UNAVAILABLE)
            }
            if (decision == null) return halted(StageDenialCodes.ROLE_TIMED_OUT)
            checkpoint()?.let { return halted(it) }
            val afterRead = captureHostCall {
                withContext(blockingDispatcher) { host.readBytes(command.outputPath) }
            }
            // Authority wins over read diagnostics; never accept against a
            // revoked lease. Confirm output integrity before checking inputs.
            if (!live(lease, snapshot)) return terminal(
                RunPreparationStageUseCaseDiagnosticCodes.STALE_ARTIFACT_APPROVAL)
            val inputStatus = verifyInputs(running, stage)
            if (inputStatus == HostReadVerification.CHANGED ||
                (afterRead.isSuccess && afterRead.getOrThrow()?.contentEquals(bytes) != true))
                return terminal(RunPreparationStageUseCaseDiagnosticCodes.STALE_ARTIFACT_APPROVAL)
            if (afterRead.isFailure) return terminal(StageDenialCodes.OUTPUT_READ_UNAVAILABLE)
            if (inputStatus == HostReadVerification.UNAVAILABLE) return terminal(StageDenialCodes.INPUT_READ_UNAVAILABLE)
            if (decision == PreparationApproval.REJECT)
                return terminal(StageDenialCodes.HOST_REJECTED, StageState.REJECTED, rejected = true)

            val draft = running.copy(
                stages = running.stages.map {
                    if (it.correlation.stageRunId == id)
                        it.copy(outputArtifacts = setOf(candidate.id)) else it
                },
                artifacts = running.artifacts + candidate
            )
            if (StageGraphPolicy.validate(draft) != StageGateDecision.Permit)
                return terminal(StageDenialCodes.INVALID_STAGE_MANIFEST)
            if (decision == PreparationApproval.HUMAN_REVIEW_REQUIRED)
                return finish(lease, draft, accepted,
                    PreparationStageOutcome.AwaitingHuman(candidate), events, candidate,
                    StageDenialCodes.HUMAN_APPROVAL_REQUIRED,
                    pending = PreparationPendingApproval(validation, proposal.usage))
            val frozen = candidate.copy(approval = ArtifactApproval.ACCEPTED,
                freeze = ArtifactFreeze.FROZEN)
            val approved = draft.copy(artifacts = draft.artifacts.map {
                if (it.id == frozen.id) frozen else it
            })
            val acceptedIds = accepted + frozen.id
            val completed = transition(approved, id, StageState.ACCEPTED, acceptedIds)
                ?: return terminal(StageDenialCodes.STAGE_ACCEPTANCE_DENIED)
            return finish(lease, completed, acceptedIds,
                PreparationStageOutcome.Accepted(frozen, validation, proposal.usage),
                events, frozen)
        } catch (failure: Exception) {
            val cancelled = callerCancellation(failure)
            if (cancelled == null) {
                stopped(command)?.let { return halted(it) }
                return terminal("PREPARATION_STAGE_FAILED")
            }
            withContext(NonCancellable) {
                try { terminal(StageDenialCodes.ROLE_CANCELLED, StageState.CANCELLED) }
                catch (cleanup: Exception) {
                    if (cleanup !== cancelled) cancelled.addSuppressed(cleanup)
                }
            }
            throw cancelled
        }
    }

    /**
     * Explicit completion for a previously committed RUNNING/DRAFT human gate.
     * Validation evidence must originate from the host's own stored record,
     * not from the caller, agent or a serialized draft.
     */
    suspend fun completeHumanApproval(
        expectedGraph: StageGraph, acceptedIds: Set<ArtifactId>,
        stageRunId: StageRunId, deadline: Instant, cancellation: Cancellation,
        events: PreparationStageEventSink
    ): PreparationStageResult {
        fun stoppedHuman(): String? {
            val signals = cancellation.signals()
            return when {
                signals.requested || signals.expired -> RunPreparationStageUseCaseDiagnosticCodes.HUMAN_APPROVAL_STOPPED
                !Instant.now(clock).isBefore(deadline) ->
                    RunPreparationStageUseCaseDiagnosticCodes.HUMAN_APPROVAL_TIMED_OUT
                else -> null
            }
        }
        fun denied(code: String) = PreparationStageResult(expectedGraph, acceptedIds,
            PreparationStageOutcome.Denied(code))
        val stage = expectedGraph.stages.firstOrNull {
            it.correlation.stageRunId == stageRunId
        } ?: return denied(StageDenialCodes.UNKNOWN_STAGE)
        if (stage.kind !in kinds || stage.state != StageState.RUNNING ||
            stage.outputArtifacts.size != 1) return denied(
                RunPreparationStageUseCaseDiagnosticCodes.NO_PENDING_HUMAN_APPROVAL)
        val candidate = expectedGraph.artifacts.singleOrNull {
            it.id in stage.outputArtifacts && it.approval == ArtifactApproval.DRAFT &&
                it.freeze == ArtifactFreeze.EDITABLE
        } ?: return denied(RunPreparationStageUseCaseDiagnosticCodes.NO_PENDING_HUMAN_APPROVAL)
        stoppedHuman()?.let { return denied(it) }
        val lease = try {
            withContext(NonCancellable + blockingDispatcher) {
                host.beginHumanApproval(expectedGraph, stageRunId, acceptedIds)
            }
        } catch (_: Exception) {
            return PreparationStageResult(null, null,
                PreparationStageOutcome.ReconciliationRequired("HUMAN_APPROVAL_BEGIN_UNCONFIRMED"))
        } ?: return denied(StageDenialCodes.STAGE_ADMISSION_REVOKED)
        val pending = lease.pendingApproval
        val validation = pending?.validation
        val validHumanLease = try {
            lease.graph == expectedGraph && lease.acceptedIds == acceptedIds &&
                lease.stageRunId == stageRunId &&
                ToolAuthorizationPolicy.validHostIdentifier(lease.generation) &&
                validation != null && validation.successful &&
                validation.correlation == stage.correlation && validation.actor == stage.agentId &&
                pending != null &&
                validation.requiredChecks.map { it.checkId }.toSet().let { checks ->
                    StageCheckIds.GIT_DIFF_CHECK in checks && checks.any { it != StageCheckIds.GIT_DIFF_CHECK }
                } && validation.requiredChecks.all { it.passed }
        } catch (_: Exception) { false }
        if (!validHumanLease)
            return withContext(NonCancellable) {
                abort(lease, StageState.FAILED,
                    RunPreparationStageUseCaseDiagnosticCodes.INVALID_HUMAN_APPROVAL_LEASE, events)
            }
        val approvedEvidence = requireNotNull(validation)
        val originalUsage = requireNotNull(pending).usage
        suspend fun failed(code: String): PreparationStageResult =
            finish(lease, expectedGraph, acceptedIds,
                PreparationStageOutcome.Failed(code), events, code = code,
                pending = pending)
        suspend fun stale(code: String): PreparationStageResult =
            abort(lease, StageState.FAILED, code, events)
        try {
            val initialCurrent = captureHostCall {
                withContext(blockingDispatcher) { host.current(lease) }
            }.getOrElse { return failed(
                RunPreparationStageUseCaseDiagnosticCodes.HUMAN_APPROVAL_AUTHORITY_UNAVAILABLE) }
            if (!initialCurrent) return stale(RunPreparationStageUseCaseDiagnosticCodes.INVALID_HUMAN_APPROVAL_LEASE)
            // A confirmed change wins over transient read failures, regardless of path order.
            val before = captureHostCall {
                withContext(blockingDispatcher) { host.readBytes(candidate.declaredPath) }
            }
            val beforeInputs = verifyInputs(expectedGraph, stage)
            if (beforeInputs == HostReadVerification.CHANGED ||
                (before.isSuccess && before.getOrThrow()?.let { digest(it) == candidate.sha256 } != true))
                return stale(RunPreparationStageUseCaseDiagnosticCodes.STALE_HUMAN_APPROVAL)
            if (beforeInputs == HostReadVerification.UNAVAILABLE || before.isFailure)
                return failed(RunPreparationStageUseCaseDiagnosticCodes.HUMAN_APPROVAL_READ_UNAVAILABLE)
            val bytes = requireNotNull(before.getOrThrow())
            stoppedHuman()?.let { return failed(it) }
            val decision = try {
                withContext(blockingDispatcher) {
                    val remaining = Duration.between(Instant.now(clock), deadline).toMillis()
                    if (remaining <= 0) return@withContext null
                    withTimeoutOrNull(remaining) {
                        host.approve(candidate, approvedEvidence, originalUsage)
                    }
                }
            } catch (failure: Exception) {
                ensureHostCallerActive(failure)
                return failed(stoppedHuman() ?: StageDenialCodes.HOST_APPROVAL_UNAVAILABLE)
            }
            if (decision == null) return failed(RunPreparationStageUseCaseDiagnosticCodes.HUMAN_APPROVAL_TIMED_OUT)
            stoppedHuman()?.let { return failed(it) }
            val after = captureHostCall {
                withContext(blockingDispatcher) { host.readBytes(candidate.declaredPath) }
            }
            val afterInputs = verifyInputs(expectedGraph, stage)
            val current = captureHostCall {
                withContext(blockingDispatcher) { host.current(lease) }
            }
            if (current.getOrNull() == false || afterInputs == HostReadVerification.CHANGED ||
                (after.isSuccess && after.getOrThrow()?.contentEquals(bytes) != true))
                return stale(RunPreparationStageUseCaseDiagnosticCodes.STALE_HUMAN_APPROVAL)
            if (current.isFailure) return failed(
                RunPreparationStageUseCaseDiagnosticCodes.HUMAN_APPROVAL_AUTHORITY_UNAVAILABLE)
            if (after.isFailure || afterInputs == HostReadVerification.UNAVAILABLE)
                return failed(RunPreparationStageUseCaseDiagnosticCodes.HUMAN_APPROVAL_READ_UNAVAILABLE)
            if (decision == PreparationApproval.HUMAN_REVIEW_REQUIRED)
                return finish(lease, expectedGraph, acceptedIds,
                    PreparationStageOutcome.AwaitingHuman(candidate), events, candidate,
                    StageDenialCodes.HUMAN_APPROVAL_REQUIRED, pending = pending)
            if (decision == PreparationApproval.REJECT) {
                val rejected = transition(expectedGraph, stageRunId, StageState.REJECTED,
                    acceptedIds) ?: return failed("STAGE_REJECTION_FAILED")
                return finish(lease, rejected, acceptedIds,
                    PreparationStageOutcome.Denied(StageDenialCodes.HOST_REJECTED), events, candidate, StageDenialCodes.HOST_REJECTED)
            }
            val frozen = candidate.copy(approval = ArtifactApproval.ACCEPTED,
                freeze = ArtifactFreeze.FROZEN)
            val graph = expectedGraph.copy(artifacts = expectedGraph.artifacts.map {
                if (it.id == frozen.id) frozen else it
            })
            val accepted = acceptedIds + frozen.id
            val completed = transition(graph, stageRunId, StageState.ACCEPTED, accepted)
                ?: return failed(StageDenialCodes.STAGE_ACCEPTANCE_DENIED)
            return finish(lease, completed, accepted,
                PreparationStageOutcome.Accepted(frozen, approvedEvidence, originalUsage),
                events, frozen)
        } catch (failure: Exception) {
            val cancelled = callerCancellation(failure)
            if (cancelled == null)
                return failed(stoppedHuman() ?: "HUMAN_APPROVAL_FAILED")
            withContext(NonCancellable) {
                try { failed(RunPreparationStageUseCaseDiagnosticCodes.HUMAN_APPROVAL_STOPPED) }
                catch (cleanup: Exception) {
                    if (cleanup !== cancelled) cancelled.addSuppressed(cleanup)
                }
            }
            throw cancelled
        }
    }
}

/** Diagnostic codes emitted by RunPreparationStageUseCase; not shared protocol vocabulary. */
internal object RunPreparationStageUseCaseDiagnosticCodes {
    const val UNSUPPORTED_PREPARATION_STAGE = "UNSUPPORTED_PREPARATION_STAGE"
    const val STALE_ARTIFACT_APPROVAL = "STALE_ARTIFACT_APPROVAL"
    const val HUMAN_APPROVAL_STOPPED = "HUMAN_APPROVAL_STOPPED"
    const val HUMAN_APPROVAL_TIMED_OUT = "HUMAN_APPROVAL_TIMED_OUT"
    const val NO_PENDING_HUMAN_APPROVAL = "NO_PENDING_HUMAN_APPROVAL"
    const val INVALID_HUMAN_APPROVAL_LEASE = "INVALID_HUMAN_APPROVAL_LEASE"
    const val HUMAN_APPROVAL_AUTHORITY_UNAVAILABLE = "HUMAN_APPROVAL_AUTHORITY_UNAVAILABLE"
    const val STALE_HUMAN_APPROVAL = "STALE_HUMAN_APPROVAL"
    const val HUMAN_APPROVAL_READ_UNAVAILABLE = "HUMAN_APPROVAL_READ_UNAVAILABLE"
}

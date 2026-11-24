package ai.yuriilab.recursivecoder

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A host-selected implementation target, never a model-supplied path or digest.
 * Null means CREATE; otherwise EDIT the exact preimage. Deletions and renames
 * are deliberately unavailable during this first Developer stage.
 */
data class DeveloperFileTarget(val path: String, val expectedSha256: String? = null)

data class DeveloperChangedFile(
    val path: String,
    val beforeSha256: String?,
    val afterSha256: String
)

/** The host chooses every possible write path, the manifest path and check policy. */
data class DeveloperStageCommand(
    val graph: StageGraph,
    val hostAcceptedArtifactIds: Set<ArtifactId>,
    val stageRunId: StageRunId,
    val artifactId: ArtifactId,
    val manifestPath: String,
    val targets: List<DeveloperFileTarget>,
    val instruction: String,
    val model: ModelConfig,
    val deadline: Instant,
    val cancellation: Cancellation
)

sealed interface DeveloperStageOutcome {
    data class Accepted(
        val manifest: StageArtifactManifest,
        val changedFiles: List<DeveloperChangedFile>,
        val writeToolId: String,
        val validation: StageValidationEvidence,
        val usage: StageRoleUsage
    ) : DeveloperStageOutcome
    data class Denied(val code: String) : DeveloperStageOutcome
    /**
     * Conservative signal: a write was attempted, so bytes may have changed even
     * when a trusted write operation subsequently reported a denial or threw.
     * The host must inspect/reconcile before reuse; this is not proof of dirt.
     */
    data class Failed(val code: String, val workspaceMayBeDirty: Boolean) : DeveloperStageOutcome
    /** Host CAS may have committed. Reload authoritative graph and approvals. */
    data class ReconciliationRequired(val code: String, val workspaceMayBeDirty: Boolean) : DeveloperStageOutcome
}

data class DeveloperStageResult(
    val graph: StageGraph?,
    val hostAcceptedArtifactIds: Set<ArtifactId>?,
    val outcome: DeveloperStageOutcome
)

/**
 * One explicitly selected Developer attempt. Koog can propose source contents,
 * but cannot choose write paths, make tool calls, approve artifacts or forge checks.
 * The trusted #74 stage session owns all effects and authenticated receipts.
 *
 * A single attempt is limited to seven host-selected files plus one generated
 * manifest, matching TrustedWriteRuntime's eight-operation atomic preflight.
 */
class RunDeveloperStageUseCase(
    private val roles: StageRoleExecutor,
    private val tools: StageToolSessionOpener,
    private val host: PreparationStageHost,
    private val blockingDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC()
) {
    private val shaPattern = Regex("[0-9a-f]{64}")

    private fun sha(bytes: ByteArray): String =
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

    private fun stopped(command: DeveloperStageCommand): String? {
        val signals = command.cancellation.signals()
        return when {
            signals.expired || !Instant.now(clock).isBefore(command.deadline) -> StageDenialCodes.ROLE_TIMED_OUT
            signals.requested -> StageDenialCodes.ROLE_CANCELLED
            else -> null
        }
    }

    private suspend fun live(
        lease: PreparationStageLease, snapshot: StageToolHostSnapshot
    ): Boolean = try {
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
        try {
            val stage = graph.stages.single { it.correlation.stageRunId == id }
            events.emit(PreparationStageEvent(stage.correlation, stage.kind, stage.state,
                manifest?.approval, manifest?.id, manifest?.sha256, code))
        } catch (_: Exception) { /* Host CAS, not best-effort event delivery, is authoritative. */ }
    }

    private suspend fun finish(
        lease: PreparationStageLease, graph: StageGraph, accepted: Set<ArtifactId>,
        outcome: DeveloperStageOutcome, events: PreparationStageEventSink,
        manifest: StageArtifactManifest? = null, code: String? = null,
        workspaceMayBeDirty: Boolean
    ): DeveloperStageResult {
        currentCoroutineContext().ensureActive()
        val committed = try {
            withContext(NonCancellable + blockingDispatcher) {
                host.finishStage(lease, graph, accepted)
            }
        } catch (_: Exception) {
            return DeveloperStageResult(null, null,
                DeveloperStageOutcome.ReconciliationRequired(StageDenialCodes.STAGE_FINISH_UNCONFIRMED, workspaceMayBeDirty))
        }
        if (!committed) return DeveloperStageResult(null, null,
            DeveloperStageOutcome.ReconciliationRequired(StageDenialCodes.STALE_STAGE_LEASE, workspaceMayBeDirty))
        emit(events, graph, lease.stageRunId, manifest, code)
        return DeveloperStageResult(graph, accepted, outcome)
    }

    private suspend fun abort(
        lease: PreparationStageLease, state: StageState, code: String,
        events: PreparationStageEventSink, workspaceMayBeDirty: Boolean
    ): DeveloperStageResult {
        val snapshot = try {
            withContext(NonCancellable + blockingDispatcher) { host.abortStage(lease, state) }
        } catch (_: Exception) { null }
            ?: return DeveloperStageResult(null, null,
                DeveloperStageOutcome.ReconciliationRequired(StageDenialCodes.STAGE_ABORT_UNCONFIRMED, workspaceMayBeDirty))
        emit(events, snapshot.graph, lease.stageRunId, code = code)
        return DeveloperStageResult(snapshot.graph, snapshot.acceptedIds,
            DeveloperStageOutcome.Failed(code, workspaceMayBeDirty))
    }

    /**
     * Strict untrusted proposal: {"files":[{"path":"host-approved path","content":"source"},...]}.
     * Exactly the host-selected target set is required. Unknown fields, operation
     * names, supplied hashes, duplicate paths and non-string values fail closed.
     */
    private fun decode(text: String, targets: List<DeveloperFileTarget>): List<StageWrite>? {
        val root = try { Json.parseToJsonElement(text) as? JsonObject }
            catch (_: Exception) { null } ?: return null
        if (root.keys != setOf("files")) return null
        val files = root["files"] as? JsonArray ?: return null
        if (files.size != targets.size) return null
        val decoded = mutableMapOf<String, String>()
        for (file in files) {
            val record = file as? JsonObject ?: return null
            if (record.keys != setOf("path", "content")) return null
            val path = record["path"] as? JsonPrimitive ?: return null
            val content = record["content"] as? JsonPrimitive ?: return null
            if (!path.isString || !content.isString || content.content.isEmpty()) return null
            if (decoded.put(path.content, content.content) != null) return null
        }
        if (decoded.keys != targets.map { it.path }.toSet()) return null
        return targets.map { target ->
            val body = decoded.getValue(target.path)
            if (target.expectedSha256 == null) StageWrite.Create(target.path, body)
            else StageWrite.Edit(target.path, target.expectedSha256, body)
        }
    }

    private fun xmlAttr(text: String): String = text.replace("&", "&amp;")
        .replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    private fun manifestContent(changes: List<DeveloperChangedFile>): String =
        buildString {
            append("recursive-coder implementation manifest v1\n")
            changes.sortedBy { it.path }.forEach {
                append(it.path).append('\t')
                    .append(it.beforeSha256 ?: "-").append('\t')
                    .append(it.afterSha256).append('\n')
            }
        }

    suspend fun execute(
        command: DeveloperStageCommand,
        roleEvents: StageRoleEventSink,
        events: PreparationStageEventSink
    ): DeveloperStageResult {
        val original = command.graph
        val accepted = command.hostAcceptedArtifactIds.toSet()
        val id = command.stageRunId
        fun denied(code: String) =
            DeveloperStageResult(original, accepted, DeveloperStageOutcome.Denied(code))
        val stage = original.stages.singleOrNull { it.correlation.stageRunId == id }
            ?: return denied("UNSUPPORTED_DEVELOPER_STAGE")
        if (stage.kind != StageKind.DEVELOP || stage.supersededBy != null ||
            stage.outputArtifacts.isNotEmpty()) return denied("UNSUPPORTED_DEVELOPER_STAGE")
        val targets = command.targets.toList()
        if (targets.isEmpty() || targets.size > 7 ||
            targets.map { it.path.lowercase() }.toSet().size != targets.size ||
            command.manifestPath.lowercase() in targets.map { it.path.lowercase() } ||
            original.artifacts.any {
                it.id == command.artifactId || it.declaredPath == command.manifestPath
            }) return denied("INVALID_DEVELOPER_TARGETS")
        if (ToolAuthorizationPolicy.authorizeWrite(WriteAuthorizationFacts(
                EngineeringRole.DEVELOPER, WriteOperationKind.CREATE, command.manifestPath
            )) != ToolAuthorizationDecision.Permit) return denied("INVALID_MANIFEST_PATH")
        for (target in targets) {
            val operation = if (target.expectedSha256 == null)
                WriteOperationKind.CREATE else WriteOperationKind.EDIT
            if ((target.expectedSha256 != null && !shaPattern.matches(target.expectedSha256)) ||
                ToolAuthorizationPolicy.authorizeWrite(WriteAuthorizationFacts(
                    EngineeringRole.DEVELOPER, operation, target.path
                )) != ToolAuthorizationDecision.Permit)
                return denied("DEVELOPER_TARGET_NOT_AUTHORIZED")
        }
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
        // Read all paths: a confirmed violation must not be hidden by I/O failure.
        // Select the denial by code priority, never by file name or caller order.
        val targetReads = targets.map { target ->
            target to captureHostCall {
                withContext(blockingDispatcher) { host.readBytes(target.path) }
            }
        }
        val inputReads = inputs.map { artifact ->
            artifact to captureHostCall {
                withContext(blockingDispatcher) { host.readBytes(artifact.declaredPath) }
            }
        }
        val preflightDenials = mutableSetOf<String>()
        for ((target, read) in targetReads) {
            if (read.isFailure) {
                preflightDenials += "TARGET_READ_UNAVAILABLE"
                continue
            }
            val before = read.getOrThrow()
            when {
                target.expectedSha256 != null &&
                    (before == null || sha(before) != target.expectedSha256) ->
                    preflightDenials += "STALE_TARGET_BASELINE"
                target.expectedSha256 == null && before != null ->
                    preflightDenials += "CREATE_TARGET_EXISTS"
                before != null && utf8(before) == null ->
                    preflightDenials += "TARGET_ENCODING_INVALID"
            }
        }
        for ((artifact, read) in inputReads) {
            if (read.isFailure) {
                preflightDenials += StageDenialCodes.INPUT_READ_UNAVAILABLE
                continue
            }
            val bytes = read.getOrThrow()
            when {
                bytes == null -> preflightDenials += StageDenialCodes.ARTIFACT_MISSING
                sha(bytes) != artifact.sha256 ->
                    preflightDenials += StageDenialCodes.ARTIFACT_DIGEST_MISMATCH
                utf8(bytes) == null ->
                    preflightDenials += StageDenialCodes.ARTIFACT_ENCODING_INVALID
            }
        }
        val preflightPriority = listOf(
            "STALE_TARGET_BASELINE", "CREATE_TARGET_EXISTS", "TARGET_ENCODING_INVALID",
            StageDenialCodes.ARTIFACT_MISSING, StageDenialCodes.ARTIFACT_DIGEST_MISMATCH, StageDenialCodes.ARTIFACT_ENCODING_INVALID,
            "TARGET_READ_UNAVAILABLE", StageDenialCodes.INPUT_READ_UNAVAILABLE
        )
        preflightPriority.firstOrNull { it in preflightDenials }?.let { return denied(it) }
        val instruction = buildString {
            append(command.instruction)
            append("\n\nReturn ONLY a JSON object with a files array, one path and" +
                " complete UTF-8 source content for every host-selected target." +
                " Never propose test/spec changes, commands, renames or tool calls.")
            for ((target, read) in targetReads) {
                val before = read.getOrThrow()
                append("\n<target path=\"").append(xmlAttr(target.path)).append("\" operation=\"")
                    .append(if (target.expectedSha256 == null) "create" else "edit")
                    .append("\" />")
                if (before != null) {
                    val source = requireNotNull(utf8(before))
                    append("\n<current-source sha256=\"").append(xmlAttr(requireNotNull(target.expectedSha256)))
                        .append("\">\n")
                        .append(source.replace("&", "&amp;").replace("<", "&lt;"))
                        .append("\n</current-source>")
                }
            }
            for ((artifact, read) in inputReads) {
                val source = requireNotNull(utf8(requireNotNull(read.getOrThrow())))
                append("\n<verified-input kind=\"").append(artifact.kind.name)
                    .append("\" sha256=\"").append(xmlAttr(artifact.sha256)).append("\">\n")
                    .append(source.replace("&", "&amp;").replace("<", "&lt;"))
                    .append("\n</verified-input>")
            }
        }
        if (instruction.toByteArray(Charsets.UTF_8).size > StageRoleLimits.MAX_INSTRUCTION_BYTES)
            return denied(StageDenialCodes.STAGE_INPUT_TOO_LARGE)
        stopped(command)?.let { return denied(it) }
        val lease = try {
            withContext(NonCancellable + blockingDispatcher) {
                host.beginStage(original, id, accepted)
            }
        } catch (_: Exception) {
            return DeveloperStageResult(null, null,
                DeveloperStageOutcome.ReconciliationRequired(StageDenialCodes.STAGE_BEGIN_UNCONFIRMED, false))
        } ?: return denied(StageDenialCodes.STAGE_ADMISSION_REVOKED)
        val running = lease.graph
        val validLease = try {
            running == update(ready, id, StageState.RUNNING) &&
                lease.stageRunId == id && lease.acceptedIds == accepted &&
                ToolAuthorizationPolicy.validHostIdentifier(lease.generation) &&
                withContext(NonCancellable + blockingDispatcher) { host.current(lease) }
        } catch (_: Exception) { false }
        if (!validLease) return withContext(NonCancellable) {
            abort(lease, StageState.FAILED, StageDenialCodes.INVALID_STAGE_LEASE, events, false)
        }
        emit(events, running, id)
        var workspaceMayBeDirty = false

        suspend fun terminal(
            code: String, state: StageState = StageState.FAILED
        ): DeveloperStageResult {
            val next = transition(running, id, state, accepted)
                ?: return abort(lease, if (state == StageState.CANCELLED)
                    StageState.CANCELLED else StageState.FAILED, code, events,
                    workspaceMayBeDirty)
            return finish(lease, next, accepted,
                DeveloperStageOutcome.Failed(code, workspaceMayBeDirty), events,
                code = code, workspaceMayBeDirty = workspaceMayBeDirty)
        }
        suspend fun checkpoint(): String? {
            currentCoroutineContext().ensureActive()
            if (stopped(command) != null) return stopped(command)
            val current = captureHostCall {
                withContext(blockingDispatcher) { host.current(lease) }
            }.getOrElse { return StageDenialCodes.STAGE_AUTHORITY_UNAVAILABLE }
            return if (current) null else StageDenialCodes.STALE_STAGE_LEASE
        }
        suspend fun halted(code: String): DeveloperStageResult = terminal(code,
            if (code == StageDenialCodes.ROLE_CANCELLED || code == StageDenialCodes.ROLE_TIMED_OUT)
                StageState.CANCELLED else StageState.FAILED)

        try {
            checkpoint()?.let { return halted(it) }
            val proposal = roles.execute(StageRoleRequest(
                ready, id, stage.correlation.requestId, EngineeringRole.DEVELOPER,
                accepted, command.model, instruction, command.deadline,
                command.cancellation
            ), roleEvents)
            checkpoint()?.let { return halted(it) }
            if (proposal.correlation != stage.correlation ||
                proposal.role != EngineeringRole.DEVELOPER || proposal.agentId != stage.agentId)
                return terminal(StageDenialCodes.ROLE_RESULT_IDENTITY_MISMATCH)
            val proposed = when (val outcome = proposal.outcome) {
                is StageRoleOutcome.Proposed -> outcome.text
                is StageRoleOutcome.Denied -> return terminal(outcome.code)
                is StageRoleOutcome.Failed -> return terminal(outcome.code)
                StageRoleOutcome.Cancelled -> return halted(StageDenialCodes.ROLE_CANCELLED)
                StageRoleOutcome.TimedOut -> return halted(StageDenialCodes.ROLE_TIMED_OUT)
            }
            val usage = proposal.usage
            // Copy cross-module nullable properties into stable locals for smart casts.
            val inputTokens = usage.inputTokens
            val outputTokens = usage.outputTokens
            if (usage.modelRequests != 1 || inputTokens == null || outputTokens == null ||
                inputTokens < 0 || outputTokens < 0 ||
                inputTokens > command.graph.budget.maxTokens ||
                outputTokens > command.model.maxOutputTokens ||
                outputTokens > command.graph.budget.maxTokens - inputTokens)
                return terminal("INVALID_DEVELOPER_USAGE")
            if (proposed.isBlank() || proposed.toByteArray(Charsets.UTF_8).size >
                StageRoleLimits.MAX_PROPOSAL_BYTES) return terminal(ModelDenialCodes.MODEL_INVALID_RESPONSE)
            val writes = decode(proposed, targets)
                ?: return terminal("INVALID_DEVELOPER_PROPOSAL")
            val changed = writes.mapIndexed { index, operation ->
                val content = when (operation) {
                    is StageWrite.Create -> operation.content
                    is StageWrite.Edit -> operation.content
                    is StageWrite.Delete -> return terminal("INVALID_DEVELOPER_PROPOSAL")
                }
                DeveloperChangedFile(targets[index].path, targets[index].expectedSha256,
                    sha(content.toByteArray(Charsets.UTF_8)))
            }.sortedBy { it.path }
            val manifestText = manifestContent(changed)
            val authority = lease.authority
            val snapshot = captureHostCall {
                withContext(blockingDispatcher) { authority.current() }
            }.getOrElse { return terminal(StageDenialCodes.STAGE_AUTHORITY_UNAVAILABLE) }
                ?: return terminal(StageDenialCodes.STAGE_AUTHORITY_UNAVAILABLE)
            if (snapshot.graph != running || snapshot.stageRunId != id ||
                snapshot.requestId != stage.correlation.requestId ||
                snapshot.hostAcceptedArtifactIds != accepted ||
                !live(lease, snapshot)) return terminal(StageDenialCodes.STAGE_AUTHORITY_MISMATCH)
            val checks = snapshot.requiredChecks
            if (ToolAuthorizationPolicy.admitRequiredChecks(EngineeringRole.DEVELOPER, checks)
                    != ToolAuthorizationDecision.Permit ||
                checks.any { it != StageCheckIds.GIT_DIFF_CHECK && it !in snapshot.checks })
                return terminal(StageDenialCodes.STAGE_CHECKS_REQUIRED)
            checkpoint()?.let { return halted(it) }
            val opened = withContext(blockingDispatcher) { tools.open(authority) }
            if (opened is StageToolOpenResult.Denied) return terminal(opened.code)
            val session = (opened as StageToolOpenResult.Granted).session
            var checkDenial: String? = null
            var writeToolId: String? = null
            val checked = mutableListOf<StageCheckEvidence>()
            val verified = withStageSessionCleanup(
                close = { withContext(NonCancellable + blockingDispatcher) { session.close() } }
            ) {
                withContext(blockingDispatcher) {
                    checkDenial = checkpoint()
                    if (checkDenial != null) return@withContext null
                    // An exception or denied write might follow partial physical mutation.
                    // Mark before entering the trusted port, not after it returns.
                    workspaceMayBeDirty = true
                    val write = session.write(writes + StageWrite.Create(command.manifestPath, manifestText))
                    if (!write.permitted) {
                        checkDenial = write.deniedCode ?: StageDenialCodes.STAGE_WRITE_FAILED
                        return@withContext null
                    }
                    writeToolId = write.toolId
                    for (check in checks.sorted()) {
                        checkDenial = checkpoint() ?: if (!live(lease, snapshot))
                            StageDenialCodes.STALE_STAGE_AUTHORITY else null
                        if (checkDenial != null) return@withContext null
                        val evidence = session.runCheck(check)
                        if (!evidence.passed) {
                            checkDenial = evidence.deniedCode ?: StageDenialCodes.STAGE_CHECK_FAILED
                            return@withContext null
                        }
                        checked += evidence
                    }
                    checkDenial = checkpoint() ?: if (!live(lease, snapshot))
                        StageDenialCodes.STALE_STAGE_AUTHORITY else null
                    if (checkDenial != null) return@withContext null
                    session.verify()
                }
            }
            checkpoint()?.let { return halted(it) }
            if (!live(lease, snapshot)) return terminal(StageDenialCodes.STALE_STAGE_AUTHORITY)
            if (verified == null) return terminal(checkDenial ?: StageDenialCodes.STAGE_TOOL_VALIDATION_FAILED)
            if (!verified.successful || verified.correlation != stage.correlation ||
                verified.actor != stage.agentId || verified.requiredChecks != checked ||
                checked.map { it.checkId }.toSet() != checks ||
                checked.any { it.baselineHead != snapshot.baselineHead } ||
                checked.map { it.manifestHash }.toSet().size != 1 ||
                checked.map { it.toolId }.toSet().size != checked.size ||
                writeToolId == null || writeToolId in checked.map { it.toolId })
                return terminal(StageDenialCodes.STAGE_VALIDATION_FAILED)

            suspend fun verificationFailure(): String? {
                val outputChecks: List<Pair<String, (ByteArray?) -> Boolean>> = changed.map { file ->
                    file.path to { bytes: ByteArray? -> bytes != null && sha(bytes) == file.afterSha256 }
                } + (command.manifestPath to { bytes: ByteArray? ->
                    bytes?.contentEquals(manifestText.toByteArray(Charsets.UTF_8)) == true
                })
                val inputChecks = inputs.map { artifact ->
                    artifact.declaredPath to { bytes: ByteArray? ->
                        bytes != null && sha(bytes) == artifact.sha256
                    }
                }
                val output = verifyHostReads(outputChecks) { path ->
                    withContext(blockingDispatcher) { host.readBytes(path) }
                }
                val input = verifyHostReads(inputChecks) { path ->
                    withContext(blockingDispatcher) { host.readBytes(path) }
                }
                return when {
                    HostReadVerification.CHANGED in listOf(output, input) -> "FROZEN_INPUT_OR_OUTPUT_CHANGED"
                    output == HostReadVerification.UNAVAILABLE -> StageDenialCodes.OUTPUT_READ_UNAVAILABLE
                    input == HostReadVerification.UNAVAILABLE -> StageDenialCodes.INPUT_READ_UNAVAILABLE
                    else -> null
                }
            }
            verificationFailure()?.let { return terminal(it) }
            val candidate = StageArtifactManifest(command.artifactId,
                StageArtifactKind.IMPLEMENTATION, command.manifestPath,
                sha(manifestText.toByteArray(Charsets.UTF_8)), EngineeringRole.DEVELOPER,
                id, 1L, ArtifactApproval.DRAFT, ArtifactFreeze.EDITABLE)
            if (!live(lease, snapshot)) return terminal(StageDenialCodes.STALE_STAGE_AUTHORITY)
            val decision = try {
                withContext(blockingDispatcher) {
                    val remaining = Duration.between(Instant.now(clock), command.deadline).toMillis()
                    if (remaining <= 0) return@withContext null
                    withTimeoutOrNull(remaining) { host.approve(candidate, verified, usage) }
                }
            } catch (failure: Exception) {
                ensureHostCallerActive(failure)
                stopped(command)?.let { return halted(it) }
                return terminal(StageDenialCodes.HOST_APPROVAL_UNAVAILABLE)
            }
            if (decision == null) return halted(StageDenialCodes.ROLE_TIMED_OUT)
            checkpoint()?.let { return halted(it) }
            val verification = verificationFailure()
            if (!live(lease, snapshot)) return terminal(StageDenialCodes.STALE_STAGE_AUTHORITY)
            verification?.let { return terminal(it) }
            if (decision == PreparationApproval.HUMAN_REVIEW_REQUIRED)
                return terminal("DEVELOPER_HUMAN_APPROVAL_REQUIRED")
            if (decision == PreparationApproval.REJECT)
                return terminal(StageDenialCodes.HOST_REJECTED, StageState.REJECTED)

            val draft = running.copy(
                stages = running.stages.map {
                    if (it.correlation.stageRunId == id)
                        it.copy(outputArtifacts = setOf(command.artifactId)) else it
                },
                artifacts = running.artifacts + candidate
            )
            if (StageGraphPolicy.validate(draft) != StageGateDecision.Permit)
                return terminal(StageDenialCodes.INVALID_STAGE_MANIFEST)
            val frozen = candidate.copy(approval = ArtifactApproval.ACCEPTED,
                freeze = ArtifactFreeze.FROZEN)
            val approved = draft.copy(artifacts = draft.artifacts.map {
                if (it.id == frozen.id) frozen else it
            })
            val acceptedIds = accepted + frozen.id
            val completed = transition(approved, id, StageState.ACCEPTED, acceptedIds)
                ?: return terminal(StageDenialCodes.STAGE_ACCEPTANCE_DENIED)
            return finish(lease, completed, acceptedIds, DeveloperStageOutcome.Accepted(
                frozen, changed, requireNotNull(writeToolId), verified, usage
            ), events, frozen, workspaceMayBeDirty = workspaceMayBeDirty)
        } catch (failure: Exception) {
            val cancelled = callerCancellation(failure)
            if (cancelled == null) {
                stopped(command)?.let { return halted(it) }
                return terminal("DEVELOPER_STAGE_FAILED")
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
}

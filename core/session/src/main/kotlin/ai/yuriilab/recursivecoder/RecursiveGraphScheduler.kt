package ai.yuriilab.recursivecoder

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class RecursiveEventKind {
    STARTED, CHILD_RESERVED, DENIED, USAGE_CHARGED, INTEGRATED, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT
}

/** No prompts, model output, credentials or raw tool errors are ever emitted. */
data class RecursiveEvent(
    val kind: RecursiveEventKind,
    val graphId: GraphId,
    val stageRunId: StageRunId,
    val parentGraphId: GraphId?,
    val parentStageRunId: StageRunId?,
    val requestId: String?,
    val origin: StageKind,
    val usage: RecursiveUsage,
    val delegated: RecursiveUsage,
    val granted: RecursiveUsage,
    val code: String? = null,
    val childGraphId: GraphId? = null
)

fun interface RecursiveEventSink { fun emit(event: RecursiveEvent) }

sealed interface RecursiveExecutionOutcome {
    data object Completed : RecursiveExecutionOutcome
    data class Denied(val code: String) : RecursiveExecutionOutcome
    data class Failed(val code: String) : RecursiveExecutionOutcome
    data object Cancelled : RecursiveExecutionOutcome
    data object TimedOut : RecursiveExecutionOutcome
}

fun interface RecursiveStageExecutor {
    suspend fun execute(context: RecursiveStageContext): RecursiveExecutionOutcome
}

/**
 * A stage-scoped host port. The executor sees only its own identity and grant,
 * not a mutable scheduler, trusted write tool, or other stage's cancellation.
 */
class RecursiveStageContext internal constructor(
    private val scheduler: RecursiveGraphScheduler,
    val graphId: GraphId,
    val stageRunId: StageRunId,
    val origin: StageKind,
    val taskId: String,
    val writablePaths: Set<String>,
    val frozenPaths: Set<String>,
    val allowedReadTools: Set<String>,
    val cancellation: Cancellation
) {
    fun requestChild(proposal: RecursiveChildProposal): RecursiveDecision =
        scheduler.requestFrom(this, proposal)

    /** Host-measured usage only. Unknown usage must be charged at its granted upper bound. */
    fun charge(usage: RecursiveUsage): RecursiveDecision = scheduler.chargeFrom(this, usage)

    /** Pull only host-accepted, frozen, digest-verified child outputs into this parent. */
    fun integrateChildren(): ChildIntegrationDecision = scheduler.integrateFrom(this)
}

/**
 * In-memory, stage-owned recursive dispatcher. It does not replace the TaskManager,
 * execute model tools, commit workspaces, persist graphs or modify Session Protocol v1.
 * The host constructs this with an ALREADY admitted RUNNING stage and owns its
 * authorization/evidence sources. All mutations are serialized under one lock.
 */
class RecursiveGraphScheduler(
    root: RecursiveRootGrant,
    private val limits: RecursiveLimits,
    private val clock: Clock = Clock.systemUTC(),
    private val events: RecursiveEventSink = RecursiveEventSink { },
    private val integrationHost: RecursiveChildIntegrationHost? = null
) {
    private val lock = Any()
    private val graphBudget = root.graph.budget
    private val tokens = mutableMapOf<GraphId, Cancellation>()
    private val runningJobs = mutableMapOf<GraphId, CompletableJob>()
    private val integrated = mutableMapOf<GraphId, IntegratedChildResults>()
    private val seenRequests = mutableSetOf<Triple<GraphId, StageRunId, String>>()
    /** Each subtree has its own bounded retry ledger; siblings cannot exhaust it. */
    private val attemptedRequests = mutableMapOf<GraphId, Int>()
    private val rootId = root.graph.correlation.graphId
    private var snapshot: RecursiveSnapshot

    init {
        require(StageGraphPolicy.validate(root.graph) == StageGateDecision.Permit) {
            "Invalid authoritative stage graph"
        }
        val stage = root.graph.stages.singleOrNull {
            it.correlation.stageRunId == root.stageRunId && it.supersededBy == null
        } ?: throw IllegalArgumentException("Unknown or superseded root stage")
        require(root.graph.children.isEmpty()) {
            "Existing StageGraph children require authoritative reconciliation (#79)"
        }
        require(root.graph.state == StageGraphState.RUNNING &&
            stage.state == StageState.RUNNING &&
            ToolAuthorizationPolicy.validHostIdentifier(root.taskId)) {
            "Root stage must be running with a valid task identity"
        }
        require(limits.maxToolCalls >= 0 && limits.maxTotalChildren >= 0 &&
            limits.maxStageDurationMillis > 0 && graphBudget.maxTokens > 0 &&
            graphBudget.maxDurationMillis > 0 && graphBudget.maxDepth >= 0 &&
            graphBudget.maxChildren >= 0 &&
            limits.maxTotalChildren <= RecursiveGraphPolicy.MAX_TOTAL_CHILDREN) {
            "Invalid recursive limits"
        }
        require(root.writablePaths.none { it in root.frozenPaths } &&
            root.frozenPaths.all(ToolAuthorizationPolicy::validDeclaredPath) &&
            root.allowedReadTools.all { it in ToolAuthorizationPolicy.knownReadTools } &&
            root.writablePaths.all {
                ToolAuthorizationPolicy.authorizeWrite(WriteAuthorizationFacts(
                    stage.kind.role, WriteOperationKind.CREATE, it
                )) == ToolAuthorizationDecision.Permit
            }) { "Invalid root authority" }
        val now = clock.instant()
        require(now.isBefore(limits.deadline)) { "Expired global deadline" }
        val window = minOf(graphBudget.maxDurationMillis, limits.maxStageDurationMillis)
        val rootDeadline = minOf(limits.deadline, now.plusMillis(window))
        snapshot = RecursiveSnapshot(listOf(RecursiveNode(
            graphId = rootId, stageRunId = root.stageRunId,
            parentGraphId = null, parentStageRunId = null,
            requestId = null, origin = stage.kind, taskId = root.taskId,
            dependencies = emptySet(), depth = 0,
            writablePaths = root.writablePaths.toSet(), frozenPaths = root.frozenPaths.toSet(),
            allowedReadTools = root.allowedReadTools.toSet(),
            granted = RecursiveUsage(graphBudget.maxTokens, limits.maxToolCalls),
            deadline = rootDeadline
        )))
        check(RecursiveGraphPolicy.validate(snapshot, limits, graphBudget)
            is RecursiveDecision.Allowed)
        tokens[rootId] = Cancellation()
    }

    val rootGraphId: GraphId get() = rootId

    fun current(): RecursiveSnapshot = synchronized(lock) {
        snapshot.copy(nodes = snapshot.nodes.toList())
    }

    fun integratedResults(parentId: GraphId): IntegratedChildResults? = synchronized(lock) {
        integrated[parentId]
    }

    /** Every receipt and byte digest is re-read at parent commit; no partial merges. */
    private fun verifyIntegration(parentId: GraphId): ChildIntegrationDecision {
        val parent = snapshot.node(parentId)
            ?: return ChildIntegrationDecision.Denied("UNKNOWN_PARENT")
        val host = integrationHost
            ?: return ChildIntegrationDecision.Denied("CHILD_INTEGRATION_UNAVAILABLE")
        return try {
            val byChild = mutableMapOf<GraphId, List<VerifiedChildArtifact>>()
            for (child in snapshot.nodes.filter { it.parentGraphId == parentId }) {
                val manifests = host.manifestsFor(child)
                    ?: return ChildIntegrationDecision.Denied("CHILD_EVIDENCE_UNAVAILABLE")
                val receipts = mutableListOf<VerifiedChildArtifact>()
                for (manifest in manifests) {
                    // Read from the trusted workspace, never from model text or a
                    // digest supplied by the child executor.
                    val bytes = host.readBytes(manifest.declaredPath)
                        ?: return ChildIntegrationDecision.Denied("CHILD_ARTIFACT_MISSING")
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                    receipts += VerifiedChildArtifact(child.graphId, manifest, digest)
                }
                byChild[child.graphId] = receipts
            }
            RecursiveChildIntegrationPolicy.integrate(snapshot, parentId,
                byChild, host.acceptedArtifactIds(), host.reservedParentArtifacts(parent))
        } catch (_: Exception) {
            ChildIntegrationDecision.Denied("CHILD_EVIDENCE_UNAVAILABLE")
        }
    }

    internal fun integrateFrom(context: RecursiveStageContext): ChildIntegrationDecision =
        synchronized(lock) {
            val parent = snapshot.node(context.graphId)
                ?: return@synchronized ChildIntegrationDecision.Denied("UNKNOWN_PARENT")
            if (parent.stageRunId != context.stageRunId || parent.origin != context.origin)
                return@synchronized ChildIntegrationDecision.Denied("STAGE_OWNERSHIP_MISMATCH")
            val decision = verifyIntegration(parent.graphId)
            when (decision) {
                is ChildIntegrationDecision.Accepted -> {
                    if (integrated[parent.graphId] != decision.results) {
                        integrated[parent.graphId] = decision.results
                        for (child in decision.results.childOrder) {
                            emit(parent, RecursiveEventKind.INTEGRATED,
                                child = child, requestId = snapshot.node(child)?.requestId)
                        }
                    }
                }
                is ChildIntegrationDecision.Denied ->
                    emit(parent, RecursiveEventKind.DENIED, decision.code)
            }
            decision
        }

    private fun replace(node: RecursiveNode) {
        snapshot = snapshot.copy(nodes = snapshot.nodes.map {
            if (it.graphId == node.graphId) node else it
        })
    }

    private fun emit(
        node: RecursiveNode, kind: RecursiveEventKind, code: String? = null,
        child: GraphId? = null, requestId: String? = node.requestId
    ) {
        // Sinks run under the scheduler lock: callers MUST provide a non-blocking
        // bounded sink. Persistence should be moved to an asynchronous host adapter.
        // Events cannot grant authority or alter the result of a committed decision.
        try {
            events.emit(RecursiveEvent(kind, node.graphId, node.stageRunId,
                node.parentGraphId, node.parentStageRunId, requestId,
                node.origin, node.spent, node.delegated, node.granted, code, child))
        } catch (_: Exception) { /* Host-owned snapshots are authoritative. */ }
    }

    internal fun requestFrom(
        context: RecursiveStageContext, proposal: RecursiveChildProposal
    ): RecursiveDecision = synchronized(lock) {
        val parent = snapshot.node(context.graphId)
            ?: return@synchronized RecursiveDecision.Denied("UNKNOWN_PARENT")
        if (context.stageRunId != parent.stageRunId || context.origin != parent.origin)
            return@synchronized RecursiveDecision.Denied("STAGE_OWNERSHIP_MISMATCH")
        val validRequestId = ToolAuthorizationPolicy.validHostIdentifier(proposal.requestId)
        val identity = Triple(parent.graphId, parent.stageRunId, proposal.requestId)
        // Per-parent cap, including malformed and denied proposals.
        val attemptLimit = maxOf(16, graphBudget.maxChildren.coerceAtMost(
            RecursiveGraphPolicy.MAX_TOTAL_CHILDREN) * 4)
        val count = attemptedRequests[parent.graphId] ?: 0
        val decision = when {
            count >= attemptLimit ->
                RecursiveDecision.Denied("REQUEST_ATTEMPT_LIMIT")
            validRequestId && identity in seenRequests ->
                RecursiveDecision.Denied("REPEATED_REQUEST")
            else -> RecursiveGraphPolicy.admit(snapshot, parent.graphId, proposal,
                limits, graphBudget, clock.instant())
        }
        if (count < attemptLimit) {
            attemptedRequests[parent.graphId] = count + 1
            if (validRequestId) seenRequests += identity
        }
        fun denied(code: String): RecursiveDecision.Denied {
            emit(parent, RecursiveEventKind.DENIED, code,
                child = if (validRequestId) RecursiveGraphPolicy.childId(
                    parent.graphId, parent.stageRunId, proposal.requestId) else null,
                requestId = if (validRequestId) proposal.requestId else null)
            return RecursiveDecision.Denied(code)
        }
        when (decision) {
            is RecursiveDecision.Denied -> denied(decision.code)
            is RecursiveDecision.Allowed -> {
                val child = decision.child
                val updatedParent = parent.copy(delegated = RecursiveUsage(
                    parent.delegated.tokens + child.granted.tokens,
                    parent.delegated.toolCalls + child.granted.toolCalls
                ))
                val candidate = RecursiveSnapshot(snapshot.nodes.map {
                    if (it.graphId == parent.graphId) updatedParent else it
                } + child)
                val checked = RecursiveGraphPolicy.validate(candidate, limits, graphBudget)
                if (checked is RecursiveDecision.Denied) {
                    // The active snapshot is untouched on defensive validation failure.
                    denied("INVALID_CHILD_ADMISSION_STATE")
                } else {
                    snapshot = candidate
                    integrated.remove(parent.graphId)
                    tokens[child.graphId] = Cancellation()
                    emit(child, RecursiveEventKind.CHILD_RESERVED, child = child.graphId)
                    decision
                }
            }
        }
    }

    internal fun chargeFrom(
        context: RecursiveStageContext, usage: RecursiveUsage
    ): RecursiveDecision = synchronized(lock) {
        val node = snapshot.node(context.graphId)
            ?: return@synchronized RecursiveDecision.Denied("UNKNOWN_STAGE")
        val decision = when {
            node.stageRunId != context.stageRunId || node.origin != context.origin ->
                RecursiveDecision.Denied("STAGE_OWNERSHIP_MISMATCH")
            !clock.instant().isBefore(limits.deadline) ->
                RecursiveDecision.Denied("GLOBAL_DEADLINE")
            !clock.instant().isBefore(node.deadline) ->
                RecursiveDecision.Denied("STAGE_DEADLINE")
            tokens.getValue(node.graphId).signals().requested ->
                RecursiveDecision.Denied("STAGE_CANCELLED")
            else -> RecursiveGraphPolicy.authorizeCharge(node, usage)
        }
        when (decision) {
            is RecursiveDecision.Allowed -> {
                replace(decision.child)
                emit(decision.child, RecursiveEventKind.USAGE_CHARGED)
            }
            is RecursiveDecision.Denied -> {
                // Fail closed: an executor cannot ignore a failed usage charge and succeed.
                emit(node, RecursiveEventKind.DENIED, decision.code)
                if (node.state == RecursiveNodeState.RUNNING) {
                    replace(node.copy(state = RecursiveNodeState.FAILED))
                    tokens.getValue(node.graphId).cancel()
                    cancelDescendants(node.graphId)
                }
            }
        }
        decision
    }

    private fun cancelDescendants(parentId: GraphId) {
        // Iterative traversal: a hostile/deep tree cannot overflow the JVM call stack.
        val pending = ArrayDeque<GraphId>()
        pending.addLast(parentId)
        while (pending.isNotEmpty()) {
            val nextParent = pending.removeFirst()
            for (child in snapshot.nodes.filter { it.parentGraphId == nextParent }) {
                pending.addLast(child.graphId)
                if (child.state in setOf(RecursiveNodeState.READY, RecursiveNodeState.RUNNING)) {
                    tokens.getValue(child.graphId).cancel()
                    runningJobs[child.graphId]?.cancel()
                    replace(child.copy(state = RecursiveNodeState.CANCELLED))
                    emit(child, RecursiveEventKind.CANCELLED)
                }
            }
        }
    }

    /** Cancel an entire subtree without revoking already-completed historical facts. */
    fun cancel(id: GraphId): Boolean = synchronized(lock) {
        val node = snapshot.node(id) ?: return@synchronized false
        if (node.state in setOf(RecursiveNodeState.READY, RecursiveNodeState.RUNNING)) {
            tokens.getValue(id).cancel()
            runningJobs[id]?.cancel()
            replace(node.copy(state = RecursiveNodeState.CANCELLED))
            emit(node, RecursiveEventKind.CANCELLED)
        }
        cancelDescendants(id)
        true
    }

    /**
     * Execution has no implicit delegation. Dependencies must have SUCCEEDED;
     * the executor cannot claim success while admitted children remain incomplete.
     */
    suspend fun execute(id: GraphId, executor: RecursiveStageExecutor): RecursiveExecutionOutcome {
        val callerJob = currentCoroutineContext()[Job]
        val contextOrDenial: Pair<RecursiveStageContext?, RecursiveExecutionOutcome?> =
            synchronized(lock) {
                val node = snapshot.node(id)
                    ?: return@synchronized null to RecursiveExecutionOutcome.Denied("UNKNOWN_STAGE")
                val denial = when {
                    node.state != RecursiveNodeState.READY -> "STAGE_NOT_READY"
                    !clock.instant().isBefore(limits.deadline) -> "GLOBAL_DEADLINE"
                    !clock.instant().isBefore(node.deadline) -> "STAGE_DEADLINE"
                    node.dependencies.any {
                        snapshot.node(it)?.state != RecursiveNodeState.SUCCEEDED
                    } -> "DEPENDENCY_NOT_SUCCEEDED"
                    node.parentGraphId?.let { parentId ->
                        snapshot.node(parentId)?.state != RecursiveNodeState.RUNNING
                    } == true -> "PARENT_NOT_RUNNING"
                    else -> null
                }
                if (denial != null) {
                    emit(node, RecursiveEventKind.DENIED, denial)
                    return@synchronized null to RecursiveExecutionOutcome.Denied(denial)
                }
                // A cancellable child Job isolates stage cancellation from its caller.
                runningJobs[id] = Job(callerJob)
                replace(node.copy(state = RecursiveNodeState.RUNNING))
                emit(node, RecursiveEventKind.STARTED)
                RecursiveStageContext(this, id, node.stageRunId, node.origin, node.taskId,
                    node.writablePaths, node.frozenPaths, node.allowedReadTools,
                    tokens.getValue(id)) to null
            }
        val context = contextOrDenial.first ?: return contextOrDenial.second!!
        val millis = synchronized(lock) {
            val deadline = snapshot.node(id)!!.deadline
            Duration.between(clock.instant(), deadline).toMillis().coerceAtLeast(1)
        }
        val stageJob = synchronized(lock) { runningJobs.getValue(id) }
        val outcome = try {
            // withTimeoutOrNull catches only THIS timeout, not enclosing scope timeouts.
            withContext(stageJob) {
                withTimeoutOrNull(millis) { executor.execute(context) }
                    ?: RecursiveExecutionOutcome.TimedOut
            }
        } catch (cancelled: CancellationException) {
            if (callerJob?.isActive == false) {
                // The invoking scope was cancelled. Record the terminal state and
                // rethrow so structured concurrency is preserved.
                synchronized(lock) {
                    runningJobs.remove(id)?.cancel()
                    val node = snapshot.node(id)!!
                    if (node.state == RecursiveNodeState.RUNNING) {
                        tokens.getValue(id).cancel()
                        replace(node.copy(state = RecursiveNodeState.CANCELLED))
                        emit(node.copy(state = RecursiveNodeState.CANCELLED),
                            RecursiveEventKind.CANCELLED)
                    }
                    cancelDescendants(id)
                }
                throw cancelled
            }
            // The caller is still active. Only a cancellation of this stage's
            // Job is an authorized host cancellation. A stray CancellationException
            // thrown by an executor must not be reported as a host cancellation.
            if (stageJob.isCancelled) RecursiveExecutionOutcome.Cancelled
            else RecursiveExecutionOutcome.Failed("STAGE_EXECUTOR_FAILED")
        } catch (_: InterruptedException) {
            RecursiveExecutionOutcome.Cancelled
        } catch (_: Exception) {
            RecursiveExecutionOutcome.Failed("STAGE_EXECUTOR_FAILED")
        }
        return synchronized(lock) {
            runningJobs.remove(id)?.complete()
            val node = snapshot.node(id)!!
            if (node.state == RecursiveNodeState.CANCELLED)
                return@synchronized RecursiveExecutionOutcome.Cancelled
            if (node.state == RecursiveNodeState.FAILED)
                return@synchronized RecursiveExecutionOutcome.Failed("USAGE_CHARGE_DENIED")
            val final = when {
                !clock.instant().isBefore(node.deadline) ||
                    !clock.instant().isBefore(limits.deadline) ||
                    outcome == RecursiveExecutionOutcome.TimedOut ->
                    RecursiveExecutionOutcome.TimedOut
                tokens.getValue(id).signals().requested ||
                    outcome == RecursiveExecutionOutcome.Cancelled ->
                    RecursiveExecutionOutcome.Cancelled
                outcome == RecursiveExecutionOutcome.Completed &&
                    snapshot.nodes.any { it.parentGraphId == id &&
                        it.state != RecursiveNodeState.SUCCEEDED } ->
                    RecursiveExecutionOutcome.Failed("CHILD_WORK_INCOMPLETE")
                outcome == RecursiveExecutionOutcome.Completed && integrationHost != null &&
                    snapshot.nodes.any { it.parentGraphId == id } &&
                    integrated[id] == null ->
                    RecursiveExecutionOutcome.Failed("CHILD_RESULTS_NOT_INTEGRATED")
                outcome == RecursiveExecutionOutcome.Completed && integrationHost != null &&
                    snapshot.nodes.any { it.parentGraphId == id } &&
                    (verifyIntegration(id) as? ChildIntegrationDecision.Accepted)?.results !=
                        integrated[id] ->
                    RecursiveExecutionOutcome.Failed("CHILD_INTEGRATION_STALE")
                else -> outcome
            }
            val terminal = when (final) {
                RecursiveExecutionOutcome.Completed -> RecursiveNodeState.SUCCEEDED
                is RecursiveExecutionOutcome.Denied, is RecursiveExecutionOutcome.Failed ->
                    RecursiveNodeState.FAILED
                RecursiveExecutionOutcome.Cancelled -> RecursiveNodeState.CANCELLED
                RecursiveExecutionOutcome.TimedOut -> RecursiveNodeState.TIMED_OUT
            }
            replace(node.copy(state = terminal))
            val code = when (final) {
                is RecursiveExecutionOutcome.Denied -> final.code
                is RecursiveExecutionOutcome.Failed -> final.code
                else -> null
            }
            val kind = when (terminal) {
                RecursiveNodeState.SUCCEEDED -> RecursiveEventKind.SUCCEEDED
                RecursiveNodeState.FAILED -> RecursiveEventKind.FAILED
                RecursiveNodeState.CANCELLED -> RecursiveEventKind.CANCELLED
                RecursiveNodeState.TIMED_OUT -> RecursiveEventKind.TIMED_OUT
                else -> error("Not a terminal state")
            }
            emit(node.copy(state = terminal), kind, code)
            if (terminal != RecursiveNodeState.SUCCEEDED) {
                tokens.getValue(id).cancel()
                cancelDescendants(id)
            }
            final
        }
    }
}

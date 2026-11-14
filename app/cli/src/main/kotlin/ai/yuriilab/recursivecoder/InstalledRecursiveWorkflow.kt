package ai.yuriilab.recursivecoder

import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * Opt-in, fake-only installed workflow bridge. Child workspaces are independently
 * provisioned isolated Linux Git checkouts. No child can write to the parent
 * checkout; the parent repeats the accepted bytes through TrustedWriteRuntime.
 * This bridge gates transitions on *both* authenticated host receipts.
 */
internal data class InstalledChildCheckout(
    val root: Path,
    val head: String,
    val branch: String,
    val locks: Path,
    val requestId: String,
    val dependsOnSelf: Boolean = false,
    val outputArtifactId: ArtifactId? = null,
    val maxTokens: Long? = null
)

/**
 * Pure second gate after the Linux hosts independently authenticate the real
 * write/check receipts. This function does NOT authenticate its arguments;
 * callers must retrieve them from LinuxFiveRoleWorkflowHost, not a model.
 * Stable mismatch codes make each digest/scope/integration branch testable.
 */
internal object InstalledRecursiveEvidencePolicy {
    fun mismatch(kind: StageKind, target: FiveRoleArtifactTarget,
        parent: AuthenticatedAcceptedStageReceipt,
        child: AuthenticatedAcceptedStageReceipt,
        integrated: StageArtifactManifest?,
        parentBytes: ByteArray?, childBytes: ByteArray?): String? {
        if (parent.origin != kind || child.origin != kind)
            return "CHILD_ORIGIN_MISMATCH"
        if (parent.manifest.id != target.id || child.manifest.id != target.id)
            return "CHILD_ARTIFACT_ID_MISMATCH"
        if (parent.manifest.declaredPath != target.path ||
            child.manifest.declaredPath != target.path)
            return "CHILD_ARTIFACT_PATH_MISMATCH"
        if (parent.manifest.sha256 != child.manifest.sha256)
            return "CHILD_ARTIFACT_DIGEST_MISMATCH"
        if (parent.writtenPostimages != child.writtenPostimages)
            return "CHILD_WRITTEN_POSTIMAGES_MISMATCH"
        if (kind == StageKind.DEVELOP) {
            if (integrated == null) return "CHILD_RESULT_NOT_INTEGRATED"
            if (integrated != child.manifest)
                return "CHILD_INTEGRATED_MANIFEST_MISMATCH"
        }
        if (parentBytes == null || childBytes == null)
            return "CHILD_ARTIFACT_BYTES_MISSING"
        if (!parentBytes.contentEquals(childBytes))
            return "CHILD_ARTIFACT_BYTES_MISMATCH"
        val hash = MessageDigest.getInstance("SHA-256").digest(parentBytes)
            .joinToString("") { "%02x".format(it) }
        if (hash != parent.manifest.sha256)
            return "CHILD_ARTIFACT_CONTENT_DIGEST_MISMATCH"
        return null
    }
}

/**
 * Pure admission decision, including deliberate self-cycles and a bounded
 * tool-call grant. No child host, Git checkout or model call can run here.
 */
/**
 * Pure origin projection and authenticated-parent identity precheck. A projected
 * grant is never sufficient to begin a trusted Linux child write; the production
 * host and recursive scheduler independently verify their current leases.
 */
internal object InstalledRecursiveOriginPolicy {
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun liveStage(request: StageRoleRequest, kind: StageKind,
        live: PreparationStageSnapshot, sessionId: String): StageNode? {
        val stage = live.graph.stages.singleOrNull {
            it.correlation.stageRunId == request.stageRunId &&
                it.kind == kind && it.state == StageState.RUNNING
        } ?: return null
        val requested = request.graph.stages.singleOrNull {
            it.correlation.stageRunId == request.stageRunId
        } ?: return null
        if (live.graph.correlation.sessionId != sessionId ||
            request.graph.correlation != live.graph.correlation ||
            request.expectedRole != kind.role ||
            requested.kind != kind || requested.state != StageState.READY ||
            requested.correlation != stage.correlation ||
            stage.correlation.requestId != request.requestId)
            return null
        return stage
    }

    fun project(sessionId: String, stage: StageNode, parentGraphId: GraphId,
        frozen: Set<String>, targets: Map<StageKind, FiveRoleArtifactTarget>,
        developerTargets: List<DeveloperFileTarget>,
        budget: StageBudget): RecursiveRootGrant? {
        val kind = stage.kind
        val id = stage.correlation.stageRunId
        val rootId = GraphId("origin-" +
            digest(parentGraphId.value + ":" + id.value))
        val projectedStage = stage.copy(
            correlation = stage.correlation.copy(graphId = rootId),
            dependencies = emptySet(), inputArtifacts = emptySet(),
            outputArtifacts = emptySet()
        )
        val graph = StageGraph(
            GraphCorrelation(sessionId, rootId,
                "origin-" + digest(id.value).take(24),
                causationId = stage.correlation.requestId),
            StageGraphState.RUNNING, listOf(projectedStage), emptyList(), budget
        )
        if (StageGraphPolicy.validate(graph) != StageGateDecision.Permit) return null
        val target = targets[kind] ?: return null
        val writable = if (kind == StageKind.SDD) {
            setOf(target.path)
        } else {
            developerTargets.mapTo(mutableSetOf()) { it.path } + target.path
        }
        if (writable.any { it in frozen }) return null
        return RecursiveRootGrant(graph, id,
            "origin-task-" + kind.name.lowercase(), writable, frozen)
    }
}

internal object InstalledRecursiveAdmissionPolicy {
    fun preflight(
        kind: StageKind, grant: RecursiveRootGrant,
        requestId: String, dependsOnSelf: Boolean,
        tokens: Long, toolCalls: Long,
        limits: RecursiveLimits, now: Instant
    ): String? {
        if (!now.isBefore(limits.deadline)) return "GLOBAL_DEADLINE"
        val budget = grant.graph.budget
        val root = RecursiveNode(
            grant.graph.correlation.graphId, grant.stageRunId,
            null, null, null, kind, grant.taskId,
            emptySet(), 0, grant.writablePaths, grant.frozenPaths,
            grant.allowedReadTools,
            RecursiveUsage(budget.maxTokens, limits.maxToolCalls),
            deadline = minOf(limits.deadline,
                now.plusMillis(minOf(budget.maxDurationMillis,
                    limits.maxStageDurationMillis))),
            state = RecursiveNodeState.RUNNING
        )
        val childId = RecursiveGraphPolicy.childId(root.graphId,
            root.stageRunId, requestId)
        val proposed = RecursiveChildProposal(
            requestId, root.graphId, root.stageRunId, kind,
            "child-task-" + kind.name.lowercase(),
            dependsOn = if (dependsOnSelf) setOf(childId) else emptySet(),
            writablePaths = root.writablePaths, maxTokens = tokens,
            maxToolCalls = toolCalls,
            maxDurationMillis = minOf(60_000L, limits.maxStageDurationMillis)
        )
        val decision = RecursiveGraphPolicy.admit(
            RecursiveSnapshot(listOf(root)), root.graphId, proposed,
            limits, budget, now)
        return (decision as? RecursiveDecision.Denied)?.code
    }
}

internal class InstalledRecursiveWorkflow(
    private val sessionId: String,
    private val parent: LinuxFiveRoleWorkflowHost,
    private val check: StageIsolatedCheck,
    private val targets: Map<StageKind, FiveRoleArtifactTarget>,
    private val instructions: Map<StageKind, String>,
    private val developerTargets: List<DeveloperFileTarget>,
    private val approvedDigests: Map<StageKind, String>,
    private val model: ModelConfig,
    private val deadline: Instant,
    private val cancellation: Cancellation,
    private val childExecutor: StageRoleExecutor,
    private val children: Map<StageKind, InstalledChildCheckout>,
    private val maxTokens: Long,
    private val maxDurationMillis: Long,
    private val maxDepth: Int,
    private val maxChildren: Int,
    private val maxToolCalls: Long,
    private val childTokens: Long,
    private var journal: SessionEventJournal?
) : FiveRoleWorkflowHost {
    private data class Origin(
        val kind: StageKind,
        val grant: RecursiveRootGrant,
        val host: LinuxFiveRoleWorkflowHost,
        val stageId: StageRunId,
        val config: InstalledChildCheckout
    )

    private val origins = linkedMapOf<StageKind, Origin>()
    private var integrated: TwoOriginRecursiveOutcome.Completed? = null
    @Volatile private var uncertainChildExecution = false
    val reconciliationRequired: Boolean get() = uncertainChildExecution
    private val requiredChildChecks = setOf("git_diff_check", "offline-check")
    private val childToolCalls = requiredChildChecks.size.toLong() + 1L
    private fun tokenGrant(kind: StageKind): Long =
        children.getValue(kind).maxTokens ?: childTokens
    private val recursiveEvents: RecursiveEventSink
        get() = journal?.let(::CorrelatedRecursiveSessionEvents) ?: RecursiveEventSink { }

    fun attachJournal(value: SessionEventJournal?) {
        check(journal == null) { "Recursive journal already attached" }
        journal = value
    }

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun projectedGrant(stage: StageNode, parentGraphId: GraphId,
        frozen: Set<String>): RecursiveRootGrant? =
        InstalledRecursiveOriginPolicy.project(sessionId, stage, parentGraphId,
            frozen, targets, developerTargets,
            StageBudget(maxTokens, maxDurationMillis, maxDepth, maxChildren))

    /** Real host snapshot and request provenance are mandatory at runtime. */
    private fun rootFor(request: StageRoleRequest, kind: StageKind): RecursiveRootGrant? {
        val live = parent.snapshot()
        val stage = InstalledRecursiveOriginPolicy.liveStage(
            request, kind, live, sessionId) ?: return null
        val frozen = live.graph.artifacts.filter { it.id in live.acceptedIds }
            .mapTo(mutableSetOf()) { it.declaredPath }
        return projectedGrant(stage, live.graph.correlation.graphId, frozen)
    }

    /**
     * At the *first* SDD hook, preflight Developer as a future, non-executable
     * policy-only projection. The real Developer lease ID does not yet exist
     * (it will be generated after TDD), so this cannot be a runtime grant.
     * Actual Developer admission is repeated once the host owns that lease.
     */
    private fun preflightFutureDeveloper(request: StageRoleRequest): String? {
        val graphId = request.graph.correlation.graphId
        val futureId = StageRunId("preflight-develop-" +
            digest(graphId.value + ":DEVELOP").take(24))
        val futureRequest = "preflight-request-develop"
        val stage = StageNode(
            StageCorrelation(sessionId, graphId, futureId,
                futureRequest, "preflight-develop-correlation"),
            StageKind.DEVELOP, StageState.RUNNING,
            AgentId("preflight-develop-actor"))
        val frozen = setOf(StageKind.SDD, StageKind.ATDD, StageKind.TDD)
            .mapTo(mutableSetOf()) { targets.getValue(it).path }
        val projected = projectedGrant(stage, graphId, frozen)
            ?: return "RECURSIVE_DEVELOPER_PREFLIGHT_INVALID"
        return preflight(StageKind.DEVELOP, projected)
    }

    /** One canonical child target plan for both approvals and execution. */
    private fun childTargets(kind: StageKind, childId: GraphId):
        Map<StageKind, FiveRoleArtifactTarget> {
        if (kind == StageKind.SDD) return targets
        return targets.mapValues { (stage, value) ->
            if (stage == StageKind.DEVELOP) {
                value.copy(id = children.getValue(kind).outputArtifactId ?: value.id)
            } else {
                value.copy(id = ArtifactId("bootstrap-" + stage.name.lowercase() +
                    "-" + digest(childId.value).take(12)))
            }
        }
    }

    private fun childHost(kind: StageKind, grant: RecursiveRootGrant):
        Pair<LinuxFiveRoleWorkflowHost, StageRunId> {
        val config = children.getValue(kind)
        val childId = RecursiveGraphPolicy.childId(grant.graph.correlation.graphId,
            grant.stageRunId, config.requestId)
        val childStageId = RecursiveGraphPolicy.childStageId(childId)
        val firstStageId = if (kind == StageKind.SDD) childStageId
            else StageRunId("bootstrap-" + digest(childId.value).take(32))
        val firstRequest = if (kind == StageKind.SDD) config.requestId
            else "bootstrap-" + digest(config.requestId).take(32)
        val childGraph = StageGraph(
            GraphCorrelation(sessionId, childId,
                "child-" + digest(childId.value).take(24),
                parentGraphId = grant.graph.correlation.graphId,
                parentStageRunId = grant.stageRunId),
            StageGraphState.RUNNING,
            listOf(StageNode(StageCorrelation(sessionId, childId,
                firstStageId, firstRequest,
                "stage-" + digest(firstStageId.value).take(24)),
                StageKind.SDD, StageState.CREATED,
                AgentId("actor-" + digest(firstStageId.value).take(24)))),
            emptyList(), StageBudget(maxTokens, maxDurationMillis, 0, 0)
        )
        val childTargets = childTargets(kind, childId)
        val host = LinuxFiveRoleWorkflowHost(
            config.root, childGraph, config.head, config.branch,
            mapOf("offline-check" to check),
            requiredChildChecks,
            { candidate, _, _ ->
                val stage = listOf(StageKind.SDD, StageKind.ATDD, StageKind.TDD,
                    StageKind.DEVELOP).singleOrNull { it.role == candidate.ownerRole }
                val target = stage?.let(childTargets::get)
                if (target?.id == candidate.id && target.path == candidate.declaredPath &&
                    stage?.let(approvedDigests::get) == candidate.sha256)
                    PreparationApproval.ACCEPT else PreparationApproval.REJECT
            },
            config.locks,
            if (kind == StageKind.DEVELOP) mapOf(StageKind.DEVELOP to
                (childStageId to config.requestId)) else emptyMap()
        )
        return host to childStageId
    }

    private fun preflight(kind: StageKind, grant: RecursiveRootGrant): String? {
        val config = children.getValue(kind)
        return InstalledRecursiveAdmissionPolicy.preflight(
            kind, grant, config.requestId, config.dependsOnSelf,
            tokenGrant(kind), childToolCalls,
            RecursiveLimits(maxToolCalls, maxChildren, maxDurationMillis, deadline),
            Instant.now())
    }

    /**
     * On a failed isolated SDD child, distinguish an unchanged initial Git
     * baseline from an uncommitted/uncertain physical write. Never convert an
     * inaccessible checkout into a safe ordinary denial.
     */
    private fun initialChildBaselineIntact(config: InstalledChildCheckout): Boolean =
        try {
            LinuxStageGitBaselineReconciler(config.root, config.locks)
                .inspectCleanBaseline(config.head, config.branch) ==
                StageGitBaselineResult.Clean
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    private suspend fun produce(kind: StageKind, grant: RecursiveRootGrant): String? {
        val (host, childStageId) = childHost(kind, grant)
        val config = children.getValue(kind)
        if (kind == StageKind.SDD) {
            val before = host.snapshot()
            val target = targets.getValue(StageKind.SDD)
            val result = RunPreparationStageUseCase(childExecutor, host, host)
                .execute(PreparationStageCommand(before.graph, before.acceptedIds,
                    childStageId, target.id, target.path,
                    instructions.getValue(StageKind.SDD), model, deadline, cancellation),
                    journal?.let(::CorrelatedRoleSessionEvents) ?: StageRoleEventSink { },
                    journal?.let(::CorrelatedPreparationSessionEvents)
                        ?: PreparationStageEventSink { })
            if (result.outcome !is PreparationStageOutcome.Accepted) {
                if (result.outcome is PreparationStageOutcome.ReconciliationRequired ||
                    !initialChildBaselineIntact(config))
                    uncertainChildExecution = true
                return "CHILD_SDD_NOT_ACCEPTED"
            }
        } else {
            val childId = host.snapshot().graph.correlation.graphId
            val childTargets = childTargets(kind, childId)
            val runner = RunFiveRoleWorkflowUseCase(
                RunPreparationStageUseCase(childExecutor, host, host),
                RunDeveloperStageUseCase(childExecutor, host, host),
                RunReviewerStageUseCase(childExecutor, host.reviewerHost), host)
            val result = runner.execute(
                FiveRoleWorkflowPlan(childTargets, instructions,
                    developerTargets, model, deadline, cancellation),
                journal?.let(::CorrelatedRoleSessionEvents) ?: StageRoleEventSink { },
                journal?.let(::CorrelatedPreparationSessionEvents)
                    ?: PreparationStageEventSink { },
                journal?.let(::CorrelatedReviewerSessionEvents)
                    ?: ReviewerDecisionSink { })
            if (result is FiveRoleWorkflowOutcome.ReconciliationRequired)
                uncertainChildExecution = true
            if (result !is FiveRoleWorkflowOutcome.Completed)
                return "CHILD_DEVELOPER_NOT_ACCEPTED"
        }
        if (host.authenticatedAcceptedStage(childStageId) == null)
            return "AUTHENTICATED_CHILD_RECEIPT_MISSING"
        origins[kind] = Origin(kind, grant, host, childStageId, config)
        return null
    }

    private fun source(origin: Origin): RecursiveOriginSource {
        val config = origin.config
        val grant = origin.grant
        val integration = LinuxAuthenticatedRecursiveChildIntegrationHost(
            origin.host, sessionId, setOf(origin.stageId))
        val executor = RecursiveStageExecutor { context ->
            val childId = RecursiveGraphPolicy.childId(context.graphId,
                context.stageRunId, config.requestId)
            val admission = context.requestChild(RecursiveChildProposal(
                config.requestId, context.graphId, context.stageRunId,
                context.origin, "child-task-" + origin.kind.name.lowercase(),
                dependsOn = if (config.dependsOnSelf) setOf(childId) else emptySet(),
                writablePaths = context.writablePaths, maxTokens = tokenGrant(origin.kind),
                maxToolCalls = childToolCalls, maxDurationMillis = minOf(60_000L,
                    maxDurationMillis)))
            val child = (admission as? RecursiveDecision.Allowed)?.child
                ?: return@RecursiveStageExecutor RecursiveExecutionOutcome.Denied(
                    (admission as RecursiveDecision.Denied).code)
            val done = context.executeChild(child.graphId,
                RecursiveStageExecutor { owned ->
                    val receipt = origin.host.authenticatedAcceptedStage(origin.stageId)
                        ?: return@RecursiveStageExecutor RecursiveExecutionOutcome.Failed(
                            "AUTHENTICATED_CHILD_RECEIPT_MISSING")
                    if (receipt.checkedIds != requiredChildChecks)
                        return@RecursiveStageExecutor RecursiveExecutionOutcome.Failed(
                            "CHILD_CHECK_RECEIPT_MISMATCH")
                    when (val charged = owned.charge(RecursiveUsage(
                            tokenGrant(origin.kind), childToolCalls))) {
                        is RecursiveDecision.Allowed -> RecursiveExecutionOutcome.Completed
                        is RecursiveDecision.Denied ->
                            RecursiveExecutionOutcome.Failed(charged.code)
                    }
                })
            if (done != RecursiveExecutionOutcome.Completed)
                return@RecursiveStageExecutor done
            when (val merged = context.integrateChildren()) {
                is ChildIntegrationDecision.Accepted -> RecursiveExecutionOutcome.Completed
                is ChildIntegrationDecision.Denied ->
                    RecursiveExecutionOutcome.Failed(merged.code)
            }
        }
        return RecursiveOriginSource(grant,
            RecursiveLimits(maxToolCalls, maxChildren, maxDurationMillis, deadline),
            integration, executor)
    }

    /**
     * Called only by the parent's FakeModel role port, while the parent stage
     * holds its real Linux writer lease. The two physical children are produced
     * under their respective live origins; integration is checked before the
     * parent Developer can propose source code.
     */
    suspend fun beforeProposal(request: StageRoleRequest): String? {
        val kind = StageKind.entries.singleOrNull { it.role == request.expectedRole }
            ?: return null
        if (kind !in children) return null
        if (kind in origins) return "REPEATED_RECURSIVE_ORIGIN"
        val grant = rootFor(request, kind) ?: return "RECURSIVE_PARENT_LEASE_INVALID"
        preflight(kind, grant)?.let { return it }
        // Both origin grant policies must pass before *any* child checkout
        // gets its first physical write. Refuse later-origin-only cycles and
        // budgets before starting the SDD child.
        if (kind == StageKind.SDD) {
            preflightFutureDeveloper(request)?.let { return it }
        }
        val produced = try {
            produce(kind, grant)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A child may have written or committed before the exception.
            // Never label its host state as an ordinary safe denial.
            uncertainChildExecution = true
            return "CHILD_EXECUTION_UNCONFIRMED"
        }
        if (produced != null) return produced
        if (kind == StageKind.DEVELOP) {
            val first = origins[StageKind.SDD] ?: return "MISSING_SDD_CHILD_ORIGIN"
            val second = origins.getValue(StageKind.DEVELOP)
            val result = RunTwoOriginRecursiveChildrenUseCase(events = recursiveEvents)
                .execute(source(first), source(second))
            integrated = result as? TwoOriginRecursiveOutcome.Completed
            if (integrated == null)
                return (result as TwoOriginRecursiveOutcome.Stopped).code
            // The parent Developer writer lease is intentionally live here.
            // authenticatedAcceptedStage() is deliberately unavailable while
            // any writer holds that lease. SDD was already authenticated at
            // SDD->ATDD; recheck both parent receipts after Developer commits,
            // at the idle Developer->Reviewer barrier and final graph finish.
        }
        return null
    }

    /** Bind child bytes, manifest identity and ALL written postimages to parent. */
    private fun verifiedParent(kind: StageKind, snapshot: PreparationStageSnapshot):
        Boolean {
        val origin = origins[kind] ?: return false
        val parentStage = snapshot.graph.stages.singleOrNull {
            it.kind == kind && it.state == StageState.ACCEPTED &&
                it.supersededBy == null
        } ?: return false
        val parentReceipt = parent.authenticatedAcceptedStage(
            parentStage.correlation.stageRunId) ?: return false
        val childReceipt = origin.host.authenticatedAcceptedStage(
            origin.stageId) ?: return false
        val childBytes = origin.host.readBytes(targets.getValue(kind).path)
        val parentBytes = parent.readBytes(targets.getValue(kind).path)
        val integratedManifest = integrated?.integrated?.get(kind)
            ?.artifacts?.singleOrNull()?.manifest
        return InstalledRecursiveEvidencePolicy.mismatch(kind, targets.getValue(kind),
            parentReceipt, childReceipt, integratedManifest, parentBytes, childBytes) == null
    }

    override fun snapshot(): PreparationStageSnapshot = parent.snapshot()

    override fun advance(expected: PreparationStageSnapshot, next: StageKind,
        developerHandoff: DeveloperReviewEvidence?): PreparationStageSnapshot? {
        val previous = expected.graph.stages.lastOrNull()?.kind ?: return null
        if (previous == StageKind.SDD && !verifiedParent(StageKind.SDD, expected))
            return null
        if (previous == StageKind.DEVELOP &&
            (integrated?.integrated?.keys != setOf(StageKind.SDD, StageKind.DEVELOP) ||
                !verifiedParent(StageKind.SDD, expected) ||
                !verifiedParent(StageKind.DEVELOP, expected))) return null
        return parent.advance(expected, next, developerHandoff)
    }

    override fun finishSuccess(expected: PreparationStageSnapshot,
        reviewerId: StageRunId): StageGraph? {
        if (integrated?.integrated?.keys != setOf(StageKind.SDD, StageKind.DEVELOP) ||
            !verifiedParent(StageKind.SDD, expected) ||
            !verifiedParent(StageKind.DEVELOP, expected)) return null
        return parent.finishSuccess(expected, reviewerId)
    }
}

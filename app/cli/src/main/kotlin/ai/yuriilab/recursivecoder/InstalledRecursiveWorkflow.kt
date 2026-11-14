package ai.yuriilab.recursivecoder

import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

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
    val outputArtifactId: ArtifactId? = null
)

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
    private val journal: SessionEventJournal?
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
    private val recursiveEvents: RecursiveEventSink =
        journal?.let(::CorrelatedRecursiveSessionEvents) ?: RecursiveEventSink { }

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /** Project a live RUNNING lease into a host-owned distinct origin namespace. */
    private fun rootFor(request: StageRoleRequest, kind: StageKind): RecursiveRootGrant? {
        val live = parent.snapshot()
        val stage = live.graph.stages.singleOrNull {
            it.correlation.stageRunId == request.stageRunId &&
                it.kind == kind && it.state == StageState.RUNNING
        } ?: return null
        if (request.graph.correlation != live.graph.correlation ||
            request.graph.stages.singleOrNull {
                it.correlation.stageRunId == request.stageRunId
            }?.state != StageState.READY ||
            stage.correlation.requestId != request.requestId ||
            live.graph.correlation.sessionId != sessionId) return null
        val rootId = GraphId("origin-" +
            digest(live.graph.correlation.graphId.value + ":" + request.stageRunId.value))
        val projectedStage = stage.copy(
            correlation = stage.correlation.copy(graphId = rootId),
            dependencies = emptySet(), inputArtifacts = emptySet(),
            outputArtifacts = emptySet()
        )
        val projected = StageGraph(
            GraphCorrelation(sessionId, rootId,
                "origin-" + digest(request.stageRunId.value).take(24),
                causationId = request.requestId),
            StageGraphState.RUNNING, listOf(projectedStage), emptyList(),
            StageBudget(maxTokens, maxDurationMillis, maxDepth, maxChildren)
        )
        if (StageGraphPolicy.validate(projected) != StageGateDecision.Permit) return null
        val writable = if (kind == StageKind.SDD) {
            setOf(targets.getValue(kind).path)
        } else {
            developerTargets.map { it.path }.toSet() + targets.getValue(kind).path
        }
        val frozen = live.graph.artifacts.filter { it.id in live.acceptedIds }
            .map { it.declaredPath }.toSet()
        if (writable.any { it in frozen }) return null
        return RecursiveRootGrant(projected, request.stageRunId,
            "origin-task-" + kind.name.lowercase(), writable, frozen)
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
        // The Developer child runs its own SDD/ATDD/TDD prerequisites.
        // Give its bootstrap artifacts disjoint IDs; only the Developer
        // artifact ID can be set explicitly for a conflict-negative fixture.
        val childTargets = if (kind == StageKind.SDD) targets else targets.mapValues {
            (stage, value) -> if (stage == StageKind.DEVELOP)
                value.copy(id = config.outputArtifactId ?: value.id)
            else value.copy(id = ArtifactId("bootstrap-" + stage.name.lowercase() +
                "-" + digest(childId.value).take(12)))
        }
        val host = LinuxFiveRoleWorkflowHost(
            config.root, childGraph, config.head, config.branch,
            mapOf("offline-check" to check),
            setOf("git_diff_check", "offline-check"),
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
            if (result.outcome !is PreparationStageOutcome.Accepted)
                return "CHILD_SDD_NOT_ACCEPTED"
        } else {
            val childTargets = targets.mapValues { (stage, value) ->
                if (stage == StageKind.DEVELOP)
                    value.copy(id = config.outputArtifactId ?: value.id)
                else value.copy(id = ArtifactId("bootstrap-" + stage.name.lowercase() +
                    "-" + digest(host.snapshot().graph.correlation.graphId.value).take(12)))
            }
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
                writablePaths = context.writablePaths, maxTokens = childTokens,
                maxToolCalls = 3, maxDurationMillis = minOf(60_000L,
                    maxDurationMillis)))
            val child = (admission as? RecursiveDecision.Allowed)?.child
                ?: return@RecursiveStageExecutor RecursiveExecutionOutcome.Denied(
                    (admission as RecursiveDecision.Denied).code)
            val done = context.executeChild(child.graphId,
                RecursiveStageExecutor { owned ->
                    val receipt = origin.host.authenticatedAcceptedStage(origin.stageId)
                        ?: return@RecursiveStageExecutor RecursiveExecutionOutcome.Failed(
                            "AUTHENTICATED_CHILD_RECEIPT_MISSING")
                    when (val charged = owned.charge(RecursiveUsage(childTokens,
                            receipt.checkedIds.size.toLong() + 1))) {
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
        val produced = try { produce(kind, grant) } catch (_: Exception) {
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
            if (!verifiedParent(StageKind.SDD, parent.snapshot()))
                return "CHILD_PARENT_ARTIFACT_MISMATCH"
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
        val target = targets.getValue(kind)
        val parentManifest = parentReceipt.manifest
        val childManifest = childReceipt.manifest
        if (parentManifest.id != target.id ||
            childManifest.id != target.id ||
            parentManifest.declaredPath != target.path ||
            childManifest.declaredPath != target.path ||
            parentManifest.sha256 != childManifest.sha256 ||
            parentReceipt.writtenPostimages != childReceipt.writtenPostimages)
            return false
        if (kind == StageKind.DEVELOP) {
            val result = integrated?.integrated?.get(kind) ?: return false
            if (result.artifacts.singleOrNull()?.manifest != childManifest)
                return false
        }
        return parent.readBytes(target.path)?.contentEquals(
            origin.host.readBytes(target.path) ?: return false) == true
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

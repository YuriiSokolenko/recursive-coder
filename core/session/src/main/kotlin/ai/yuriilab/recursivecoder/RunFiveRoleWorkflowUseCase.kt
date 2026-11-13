package ai.yuriilab.recursivecoder

import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * Host-owned, opt-in five-role coordinator. This is not exposed by the legacy
 * read-only Session CLI. All writes and approvals remain inside the existing
 * preparation/developer/reviewer use cases and their independently trusted hosts.
 */
data class FiveRoleWorkflowPlan(
    val artifacts: Map<StageKind, FiveRoleArtifactTarget>,
    val instructions: Map<StageKind, String>,
    val developerTargets: List<DeveloperFileTarget>,
    val model: ModelConfig,
    val deadline: Instant,
    val cancellation: Cancellation
)

data class FiveRoleArtifactTarget(val id: ArtifactId, val path: String)

sealed interface FiveRoleWorkflowOutcome {
    data class Completed(val graph: StageGraph) : FiveRoleWorkflowOutcome
    /** Only verified host snapshots can be offered as resumable state. */
    data class Stopped(val code: String, val stage: StageKind,
                       val authoritative: PreparationStageSnapshot?) : FiveRoleWorkflowOutcome
    /** A lease/commit may have succeeded; never treat an uncertain CAS as a retryable denial. */
    data class ReconciliationRequired(val code: String, val stage: StageKind) :
        FiveRoleWorkflowOutcome
}

/**
 * Ownership port for the transitions BETWEEN separately authorized stage runs.
 *
 * An implementation must atomically reconcile a finished stage's committed
 * artifact, verified write/check receipts and frozen-input digests, establish
 * a clean, fenced Git baseline for the next stage, and then append the exact
 * next StageNode. It must not bypass TrustedWriteRuntime's clean-tree gate.
 *
 * finishSuccess must independently check the Reviewer host's authenticated
 * acceptance decision and every accepted artifact's live digest in the same
 * serialization boundary as the graph commit. A policy Permit is not evidence.
 */
interface FiveRoleWorkflowHost {
    fun snapshot(): PreparationStageSnapshot

    fun advance(
        expected: PreparationStageSnapshot,
        next: StageKind,
        developerHandoff: DeveloperReviewEvidence? = null
    ): PreparationStageSnapshot?

    fun finishSuccess(
        expected: PreparationStageSnapshot,
        reviewerId: StageRunId
    ): StageGraph?
}

/**
 * Narrow execution seam for deterministic fail-closed unit tests. Production
 * delegates to the existing authenticated stage use cases; no policy or write
 * authority is ever granted by these wrappers.
 */
interface FiveRoleStageActions {
    suspend fun prepare(
        command: PreparationStageCommand,
        roleEvents: StageRoleEventSink,
        events: PreparationStageEventSink
    ): PreparationStageResult

    suspend fun develop(
        command: DeveloperStageCommand,
        roleEvents: StageRoleEventSink,
        events: PreparationStageEventSink
    ): DeveloperStageResult

    suspend fun review(
        command: ReviewerStageCommand,
        roleEvents: StageRoleEventSink,
        events: ReviewerDecisionSink
    ): ReviewerStageResult
}

/**
 * A bounded sequential role runner. The current host snapshot is checked after
 * each existing use case and before every authority transition; callers cannot
 * synthesize acceptance from a model's successful text result.
 *
 * Recursive child execution and the installed CLI boundary are intentionally
 * separate layers. They must be wired before issue #79 can be marked complete.
 */
class RunFiveRoleWorkflowUseCase(
    private val stages: FiveRoleStageActions,
    private val host: FiveRoleWorkflowHost
) {
    constructor(
        preparations: RunPreparationStageUseCase,
        developer: RunDeveloperStageUseCase,
        reviewer: RunReviewerStageUseCase,
        host: FiveRoleWorkflowHost
    ) : this(object : FiveRoleStageActions {
        override suspend fun prepare(
            command: PreparationStageCommand,
            roleEvents: StageRoleEventSink,
            events: PreparationStageEventSink
        ) = preparations.execute(command, roleEvents, events)

        override suspend fun develop(
            command: DeveloperStageCommand,
            roleEvents: StageRoleEventSink,
            events: PreparationStageEventSink
        ) = developer.execute(command, roleEvents, events)

        override suspend fun review(
            command: ReviewerStageCommand,
            roleEvents: StageRoleEventSink,
            events: ReviewerDecisionSink
        ) = reviewer.execute(command, roleEvents, events)
    }, host)

    private val order = listOf(
        StageKind.SDD, StageKind.ATDD, StageKind.TDD, StageKind.DEVELOP,
        StageKind.REVIEW
    )

    suspend fun execute(
        plan: FiveRoleWorkflowPlan,
        roleEvents: StageRoleEventSink,
        preparationEvents: PreparationStageEventSink,
        reviewerEvents: ReviewerDecisionSink
    ): FiveRoleWorkflowOutcome {
        fun uncertain(kind: StageKind, code: String) =
            FiveRoleWorkflowOutcome.ReconciliationRequired(code, kind)
        fun stop(kind: StageKind, code: String, state: PreparationStageSnapshot?):
            FiveRoleWorkflowOutcome {
            if (state == null) return FiveRoleWorkflowOutcome.Stopped(code, kind, null)
            val latest = try { host.snapshot() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return uncertain(kind, "HOST_STOP_SNAPSHOT_UNAVAILABLE") }
            return if (latest != state) uncertain(kind, "UNCONFIRMED_STOP_STATE")
            else FiveRoleWorkflowOutcome.Stopped(code, kind, latest)
        }

        val writerKinds = order.dropLast(1).toSet()
        if (plan.artifacts.keys != writerKinds ||
            plan.instructions.keys != order.toSet() ||
            plan.instructions.values.any { it.isBlank() } ||
            plan.artifacts.values.map { it.id }.toSet().size != writerKinds.size ||
            plan.artifacts.values.map { it.path }.toSet().size != writerKinds.size ||
            plan.artifacts.any { (kind, target) ->
                ToolAuthorizationPolicy.authorizeWrite(WriteAuthorizationFacts(
                    kind.role, WriteOperationKind.CREATE, target.path
                )) != ToolAuthorizationDecision.Permit
            } || plan.developerTargets.isEmpty() ||
            plan.developerTargets.size > 7 ||
            plan.developerTargets.map { it.path }.toSet().size !=
                plan.developerTargets.size ||
            plan.developerTargets.any { target ->
                target.path in plan.artifacts.values.map { a -> a.path } ||
                ToolAuthorizationPolicy.authorizeWrite(WriteAuthorizationFacts(
                    EngineeringRole.DEVELOPER,
                    if (target.expectedSha256 == null) WriteOperationKind.CREATE
                    else WriteOperationKind.EDIT,
                    target.path
                )) != ToolAuthorizationDecision.Permit
            }) {
            return stop(StageKind.SDD, "INVALID_WORKFLOW_PLAN", null)
        }

        val initial = try { host.snapshot() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return uncertain(StageKind.SDD, "HOST_SNAPSHOT_UNAVAILABLE") }
        if (StageGraphPolicy.validate(initial.graph) != StageGateDecision.Permit ||
            initial.graph.state != StageGraphState.RUNNING ||
            initial.graph.stages.count { it.supersededBy == null } != 1 ||
            initial.graph.stages.single().kind != StageKind.SDD ||
            initial.acceptedIds.isNotEmpty())
            return stop(StageKind.SDD, "INVALID_INITIAL_STAGE_GRAPH", initial)

        var authoritative = initial
        var developerHandoff: DeveloperReviewEvidence? = null
        for ((index, kind) in order.withIndex()) {
            val before = authoritative
            val current = try { host.snapshot() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return uncertain(kind, "HOST_SNAPSHOT_UNAVAILABLE") }
            if (current != before) return uncertain(kind, "WORKFLOW_STATE_CHANGED")
            val stage = current.graph.stages.singleOrNull {
                it.kind == kind && it.supersededBy == null
            } ?: return stop(kind, "MISSING_WORKFLOW_STAGE", current)
            if (stage.state !in setOf(StageState.CREATED, StageState.READY))
                return stop(kind, "WORKFLOW_STAGE_NOT_READY", current)
            val id = stage.correlation.stageRunId

            val result = when (kind) {
                StageKind.SDD, StageKind.ATDD, StageKind.TDD -> {
                    val output = plan.artifacts.getValue(kind)
                    val done = stages.prepare(
                        PreparationStageCommand(current.graph, current.acceptedIds,
                            id, output.id, output.path,
                            plan.instructions.getValue(kind), plan.model,
                            plan.deadline, plan.cancellation),
                        roleEvents, preparationEvents)
                    when (val outcome = done.outcome) {
                        is PreparationStageOutcome.Accepted -> {
                            if (outcome.manifest.id != output.id ||
                                outcome.manifest.declaredPath != output.path)
                                return uncertain(kind, "WORKFLOW_OUTPUT_MISMATCH")
                            val graph = done.graph ?: return uncertain(kind, "MISSING_COMMITTED_GRAPH")
                            val ids = done.hostAcceptedArtifactIds
                                ?: return uncertain(kind, "MISSING_HOST_ACCEPTANCE")
                            PreparationStageSnapshot(graph, ids)
                        }
                        is PreparationStageOutcome.ReconciliationRequired ->
                            return uncertain(kind, outcome.code)
                        is PreparationStageOutcome.Denied ->
                            return stop(kind, outcome.code, current)
                        is PreparationStageOutcome.Failed ->
                            return stop(kind, outcome.code, done.graph?.let { graph ->
                                done.hostAcceptedArtifactIds?.let { ids ->
                                    PreparationStageSnapshot(graph, ids)
                                }
                            })
                        is PreparationStageOutcome.AwaitingHuman ->
                            return stop(kind, "HUMAN_APPROVAL_REQUIRED", done.graph?.let { graph ->
                                done.hostAcceptedArtifactIds?.let { ids ->
                                    PreparationStageSnapshot(graph, ids)
                                }
                            })
                    }
                }
                StageKind.DEVELOP -> {
                    val output = plan.artifacts.getValue(kind)
                    val done = stages.develop(
                        DeveloperStageCommand(current.graph, current.acceptedIds,
                            id, output.id, output.path, plan.developerTargets,
                            plan.instructions.getValue(kind), plan.model,
                            plan.deadline, plan.cancellation),
                        roleEvents, preparationEvents)
                    when (val outcome = done.outcome) {
                        is DeveloperStageOutcome.Accepted -> {
                            if (outcome.manifest.id != output.id ||
                                outcome.manifest.declaredPath != output.path)
                                return uncertain(kind, "WORKFLOW_OUTPUT_MISMATCH")
                            developerHandoff = DeveloperReviewEvidence(
                                outcome.manifest,
                                outcome.changedFiles.map { file ->
                                    ReviewerChangedFile(file.path, file.beforeSha256, file.afterSha256)
                                },
                                outcome.writeToolId, outcome.validation, outcome.usage
                            )
                            val graph = done.graph ?: return uncertain(kind, "MISSING_COMMITTED_GRAPH")
                            val ids = done.hostAcceptedArtifactIds
                                ?: return uncertain(kind, "MISSING_HOST_ACCEPTANCE")
                            PreparationStageSnapshot(graph, ids)
                        }
                        is DeveloperStageOutcome.ReconciliationRequired ->
                            return uncertain(kind, outcome.code)
                        is DeveloperStageOutcome.Denied ->
                            return stop(kind, outcome.code, current)
                        is DeveloperStageOutcome.Failed ->
                            return stop(kind, outcome.code, done.graph?.let { graph ->
                                done.hostAcceptedArtifactIds?.let { ids ->
                                    PreparationStageSnapshot(graph, ids)
                                }
                            })
                    }
                }
                StageKind.REVIEW -> {
                    val done = stages.review(
                        ReviewerStageCommand(current.graph, current.acceptedIds,
                            id, plan.model, plan.instructions.getValue(kind),
                            plan.deadline, plan.cancellation),
                        roleEvents, reviewerEvents)
                    when (val outcome = done.outcome) {
                        is ReviewerStageOutcome.Accepted -> {
                            val graph = done.graph ?: return uncertain(kind, "MISSING_COMMITTED_GRAPH")
                            val ids = done.hostAcceptedArtifactIds
                                ?: return uncertain(kind, "MISSING_HOST_ACCEPTANCE")
                            PreparationStageSnapshot(graph, ids)
                        }
                        is ReviewerStageOutcome.ReconciliationRequired ->
                            return uncertain(kind, outcome.code)
                        is ReviewerStageOutcome.Denied ->
                            return stop(kind, outcome.code, current)
                        is ReviewerStageOutcome.Failed ->
                            return stop(kind, outcome.code, done.graph?.let { graph ->
                                done.hostAcceptedArtifactIds?.let { ids ->
                                    PreparationStageSnapshot(graph, ids)
                                }
                            })
                        is ReviewerStageOutcome.Rejected ->
                            return stop(kind, "REVIEWER_REJECTED", done.graph?.let { graph ->
                                done.hostAcceptedArtifactIds?.let { ids ->
                                    PreparationStageSnapshot(graph, ids)
                                }
                            })
                        is ReviewerStageOutcome.NeedsHuman ->
                            return stop(kind, "REVIEWER_NEEDS_HUMAN", done.graph?.let { graph ->
                                done.hostAcceptedArtifactIds?.let { ids ->
                                    PreparationStageSnapshot(graph, ids)
                                }
                            })
                    }
                }
            }
            val after = try { host.snapshot() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return uncertain(kind, "HOST_SNAPSHOT_UNAVAILABLE") }
            if (after != result) return uncertain(kind, "UNCONFIRMED_STAGE_COMMIT")
            authoritative = result
            if (index < order.lastIndex) {
                val next = order[index + 1]
                authoritative = try { host.advance(result, next,
                    if (next == StageKind.REVIEW) developerHandoff else null) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return uncertain(kind, "STAGE_RECONCILIATION_UNCONFIRMED") }
                    ?: return uncertain(kind, "STAGE_RECONCILIATION_DENIED")
                if (StageGraphPolicy.validate(authoritative.graph) != StageGateDecision.Permit ||
                    authoritative.graph.stages.singleOrNull {
                        it.kind == next && it.supersededBy == null
                    } == null)
                    return uncertain(next, "INVALID_NEXT_STAGE_GRAPH")
                val confirmed = try { host.snapshot() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    return uncertain(next, "STAGE_RECONCILIATION_UNCONFIRMED")
                }
                if (confirmed != authoritative)
                    return uncertain(next, "STAGE_RECONCILIATION_UNCONFIRMED")
            }
        }
        val reviewerId = authoritative.graph.stages.single {
            it.kind == StageKind.REVIEW && it.supersededBy == null
        }.correlation.stageRunId
        // The final host CAS must attest reviewer provenance and physical bytes.
        val completed = try { host.finishSuccess(authoritative, reviewerId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return uncertain(StageKind.REVIEW, "GRAPH_FINISH_UNCONFIRMED") }
            ?: return uncertain(StageKind.REVIEW, "GRAPH_FINISH_DENIED")
        if (completed.state != StageGraphState.SUCCEEDED ||
            completed.copy(state = authoritative.graph.state) != authoritative.graph ||
            StageGraphPolicy.validate(completed) != StageGateDecision.Permit)
            return uncertain(StageKind.REVIEW, "INVALID_FINISHED_GRAPH")
        val committed = try { host.snapshot() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return uncertain(StageKind.REVIEW, "GRAPH_FINISH_UNCONFIRMED") }
        if (committed.graph != completed ||
            committed.acceptedIds != authoritative.acceptedIds)
            return uncertain(StageKind.REVIEW, "GRAPH_FINISH_UNCONFIRMED")
        return FiveRoleWorkflowOutcome.Completed(completed)
    }
}

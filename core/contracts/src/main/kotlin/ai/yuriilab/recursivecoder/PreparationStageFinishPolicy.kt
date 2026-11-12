package ai.yuriilab.recursivecoder

/**
 * Pure minimum CAS invariants for trusted [PreparationStageHost] implementations.
 *
 * The caller cannot grant an approval by supplying a manifest. [hostDecision]
 * and [hostCandidate] MUST come from the host's own generation-fenced record of
 * what its approval method returned. A Permit is not a capability or a commit.
 */
object PreparationStageFinishPolicy {
    fun permits(
        lease: PreparationStageLease,
        authoritativeGraph: StageGraph,
        authoritativeAcceptedIds: Set<ArtifactId>,
        updatedGraph: StageGraph,
        updatedAcceptedIds: Set<ArtifactId>,
        pending: PreparationPendingApproval?,
        hostDecision: PreparationApproval?,
        hostCandidate: StageArtifactManifest?
    ): Boolean {
        if (authoritativeGraph != lease.graph ||
            authoritativeAcceptedIds != lease.acceptedIds ||
            StageGraphPolicy.validate(updatedGraph) != StageGateDecision.Permit) return false

        // Do not permit changes to graph-level state or any other stage.
        if (updatedGraph.copy(
                stages = authoritativeGraph.stages, artifacts = authoritativeGraph.artifacts
            ) != authoritativeGraph ||
            updatedGraph.stages.size != authoritativeGraph.stages.size) return false
        val stages = authoritativeGraph.stages.zip(updatedGraph.stages)
        if (stages.any { (old, next) ->
                old.correlation.stageRunId != next.correlation.stageRunId ||
                    (old.correlation.stageRunId != lease.stageRunId && old != next)
            }) return false
        val priorStage = authoritativeGraph.stages.singleOrNull {
            it.correlation.stageRunId == lease.stageRunId
        } ?: return false
        val nextStage = updatedGraph.stages.singleOrNull {
            it.correlation.stageRunId == lease.stageRunId
        } ?: return false
        if (priorStage.state != StageState.RUNNING ||
            nextStage.copy(state = priorStage.state,
                outputArtifacts = priorStage.outputArtifacts) != priorStage ||
            nextStage.outputArtifacts.size > 1 ||
            priorStage.outputArtifacts.isNotEmpty() &&
                nextStage.outputArtifacts != priorStage.outputArtifacts) return false

        // Existing manifests are immutable except for the leased draft becoming
        // ACCEPTED/FROZEN; no unrelated artifact may be introduced or rewritten.
        val oldById = authoritativeGraph.artifacts.associateBy { it.id }
        val newById = updatedGraph.artifacts.associateBy { it.id }
        if (newById.keys.size != updatedGraph.artifacts.size ||
            oldById.keys.size != authoritativeGraph.artifacts.size) return false
        val added = newById.keys - oldById.keys
        val deleted = oldById.keys - newById.keys
        if (deleted.isNotEmpty() || added.size > 1) return false
        val outputId = nextStage.outputArtifacts.singleOrNull()
        if (added.isNotEmpty() && (added.single() != outputId ||
                priorStage.outputArtifacts.isNotEmpty())) return false
        if (priorStage.outputArtifacts.isEmpty() &&
            outputId != null && added != setOf(outputId)) return false
        for ((id, old) in oldById) {
            val next = newById[id] ?: return false
            if (next != old &&
                (id != outputId || old.producerStageRunId != lease.stageRunId ||
                    old.approval != ArtifactApproval.DRAFT ||
                    old.freeze != ArtifactFreeze.EDITABLE ||
                    next != old.copy(
                        approval = ArtifactApproval.ACCEPTED,
                        freeze = ArtifactFreeze.FROZEN
                    ))) return false
        }
        val output = outputId?.let(newById::get)
        if (output != null && output.producerStageRunId != lease.stageRunId) return false

        // The accepted set can only grow on exact-identity host-authorized
        // acceptance of the one output for this lease.
        val expectedAccepted = if (nextStage.state == StageState.ACCEPTED &&
            outputId != null) authoritativeAcceptedIds + outputId
            else authoritativeAcceptedIds
        if (updatedAcceptedIds != expectedAccepted) return false

        val pendingDraft = nextStage.state == StageState.RUNNING &&
            output != null && output.approval == ArtifactApproval.DRAFT &&
            output.freeze == ArtifactFreeze.EDITABLE
        if ((pending != null) != pendingDraft) return false
        if (pendingDraft) {
            val evidence = pending?.validation ?: return false
            if (!evidence.successful || evidence.correlation != nextStage.correlation ||
                evidence.actor != nextStage.agentId ||
                evidence.requiredChecks.map { it.checkId }.toSet().let {
                    "git_diff_check" !in it || it.none { check -> check != "git_diff_check" }
                } || evidence.requiredChecks.any { !it.passed }) return false
            val previouslyPending = priorStage.outputArtifacts == nextStage.outputArtifacts &&
                authoritativeGraph == updatedGraph &&
                lease.pendingApproval == pending
            if (!previouslyPending && (hostDecision != PreparationApproval.HUMAN_REVIEW_REQUIRED ||
                    hostCandidate != output)) return false
        } else if (nextStage.state == StageState.RUNNING) {
            // A new lease may not be silently consumed while the stage is
            // still RUNNING without a verified pending draft.
            return false
        }
        if (nextStage.state == StageState.ACCEPTED) {
            if (output == null ||
                output.approval != ArtifactApproval.ACCEPTED ||
                output.freeze != ArtifactFreeze.FROZEN ||
                output.id in authoritativeAcceptedIds ||
                hostDecision != PreparationApproval.ACCEPT ||
                hostCandidate != output.copy(
                    approval = ArtifactApproval.DRAFT, freeze = ArtifactFreeze.EDITABLE
                )) return false
            val priorToAcceptance = updatedGraph.copy(
                stages = updatedGraph.stages.map {
                    if (it.correlation.stageRunId == lease.stageRunId)
                        it.copy(state = StageState.RUNNING) else it
                }
            )
            if (StageGraphPolicy.authorizeStageTransition(priorToAcceptance,
                    lease.stageRunId, StageState.ACCEPTED, updatedAcceptedIds
                ) != StageGateDecision.Permit) return false
        } else if (nextStage.state != StageState.RUNNING) {
            if (nextStage.state == StageState.REJECTED &&
                hostDecision != PreparationApproval.REJECT) return false
            if (StageGraphPolicy.authorizeStageTransition(
                    authoritativeGraph, lease.stageRunId,
                    nextStage.state, authoritativeAcceptedIds
                ) != StageGateDecision.Permit) return false
        }
        return true
    }
}

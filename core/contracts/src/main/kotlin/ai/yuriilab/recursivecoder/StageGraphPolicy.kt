package ai.yuriilab.recursivecoder

/**
 * Deterministic, I/O-free authorization over host-owned snapshots. All approvals,
 * accepted ID sets and independent-review attestations MUST originate from trusted
 * host state, never an LLM response. Permits are decisions, not tool credentials.
 */
object StageGraphPolicy {
    private val digestPattern = Regex("[0-9a-f]{64}")

    private fun deny(code: String): StageGateDecision = StageGateDecision.Deny(code)
    private fun validId(value: String?): Boolean =
        value == null || ToolAuthorizationPolicy.validHostIdentifier(value)

    private fun validBudget(budget: StageBudget): Boolean =
        budget.maxTokens > 0 && budget.maxDurationMillis > 0 &&
            budget.maxDepth >= 0 && budget.maxChildren >= 0

    /** Reject malformed snapshots before making an admission or authority decision. */
    fun validate(graph: StageGraph): StageGateDecision {
        val identity = graph.correlation
        if (!validId(identity.sessionId) || !validId(identity.correlationId) ||
            !validId(identity.causationId) ||
            ((identity.parentGraphId == null) != (identity.parentStageRunId == null)) ||
            identity.parentGraphId == identity.graphId)
            return deny("INVALID_GRAPH_CORRELATION")
        if (!validBudget(graph.budget) || graph.children.size > graph.budget.maxChildren)
            return deny("INVALID_GRAPH_BUDGET")

        val stages = graph.stages.associateBy { it.correlation.stageRunId }
        val manifests = graph.artifacts.associateBy { it.id }
        if (stages.size != graph.stages.size || manifests.size != graph.artifacts.size)
            return deny("DUPLICATE_GRAPH_ID")
        for (stage in graph.stages) {
            val correlation = stage.correlation
            if (correlation.sessionId != identity.sessionId ||
                correlation.graphId != identity.graphId ||
                !validId(correlation.requestId) || !validId(correlation.correlationId) ||
                !validId(correlation.causationId))
                return deny("INVALID_STAGE_CORRELATION")
            if (stage.dependencies.any { it !in stages || it == correlation.stageRunId })
                return deny("INVALID_STAGE_DEPENDENCY")
            if (stage.agentId == null && stage.state in setOf(
                    StageState.RUNNING, StageState.ACCEPTED, StageState.REJECTED))
                return deny("MISSING_STAGE_ACTOR")
            if (stage.inputArtifacts.any { it !in manifests } ||
                stage.outputArtifacts.any { it !in manifests })
                return deny("UNKNOWN_STAGE_ARTIFACT")
            if (stage.outputArtifacts.any { id ->
                    val artifact = manifests.getValue(id)
                    artifact.producerStageRunId != correlation.stageRunId ||
                        artifact.kind != stage.kind.output
                })
                return deny("INVALID_STAGE_OUTPUT")
            if (stage.kind == StageKind.REVIEW && stage.outputArtifacts.isNotEmpty())
                return deny("INVALID_STAGE_OUTPUT")
            if (stage.kind != StageKind.REVIEW && stage.review != null)
                return deny("INVALID_STAGE_REVIEW")
            if (stage.state == StageState.ACCEPTED) {
                if (stage.kind != StageKind.REVIEW &&
                    (stage.outputArtifacts.size != 1 ||
                        stage.outputArtifacts.any { id ->
                            manifests.getValue(id).approval != ArtifactApproval.ACCEPTED ||
                                manifests.getValue(id).freeze != ArtifactFreeze.FROZEN
                        }))
                    return deny("UNACCEPTED_STAGE_OUTPUT")
                if (stage.kind == StageKind.REVIEW && stage.review?.verdict != ReviewVerdict.ACCEPT)
                    return deny("INVALID_STAGE_REVIEW")
            }
            if (stage.kind == StageKind.REVIEW && stage.review != null) {
                if (stage.state !in setOf(StageState.RUNNING, StageState.ACCEPTED,
                        StageState.REJECTED) ||
                    (stage.state == StageState.REJECTED &&
                        stage.review.verdict == ReviewVerdict.ACCEPT))
                    return deny("INVALID_STAGE_REVIEW")
            }
        }

        // Kahn-style traversal is deterministic and avoids recursive stack growth.
        val completed = mutableSetOf<StageRunId>()
        while (completed.size < stages.size) {
            val next = graph.stages.firstOrNull { stage ->
                val id = stage.correlation.stageRunId
                id !in completed && stage.dependencies.all { it in completed }
            } ?: return deny("CYCLIC_STAGE_DEPENDENCY")
            completed += next.correlation.stageRunId
        }

        for (artifact in graph.artifacts) {
            val producer = stages[artifact.producerStageRunId]
                ?: return deny("UNKNOWN_ARTIFACT_PRODUCER")
            if (artifact.revision < 1 || !digestPattern.matches(artifact.sha256) ||
                artifact.ownerRole != producer.kind.role ||
                artifact.kind != producer.kind.output ||
                artifact.id !in producer.outputArtifacts ||
                (artifact.approval == ArtifactApproval.ACCEPTED &&
                    artifact.freeze != ArtifactFreeze.FROZEN))
                return deny("INVALID_ARTIFACT_MANIFEST")
            if (ToolAuthorizationPolicy.authorizeWrite(WriteAuthorizationFacts(
                    artifact.ownerRole, WriteOperationKind.CREATE, artifact.declaredPath
                )) != ToolAuthorizationDecision.Permit)
                return deny("INVALID_ARTIFACT_PATH")
        }

        for (stage in graph.stages) {
            if (stage.inputArtifacts.any { id ->
                    manifests.getValue(id).producerStageRunId !in stage.dependencies
                })
                return deny("INVALID_INPUT_PROVENANCE")
        }

        val childIds = graph.children.map { it.graphId }
        if (childIds.toSet().size != childIds.size)
            return deny("DUPLICATE_CHILD_GRAPH")
        for (child in graph.children) {
            if (child.graphId == identity.graphId || child.parentStageRunId !in stages ||
                child.dependsOnStages.any { it !in stages || it == child.parentStageRunId } ||
                !validBudget(child.budget) ||
                child.depth !in 1..graph.budget.maxDepth ||
                child.budget.maxDepth > graph.budget.maxDepth - child.depth ||
                child.budget.maxTokens > graph.budget.maxTokens ||
                child.budget.maxDurationMillis > graph.budget.maxDurationMillis ||
                child.budget.maxChildren > graph.budget.maxChildren)
                return deny("INVALID_CHILD_REFERENCE")
        }
        return StageGateDecision.Permit
    }

    /**
     * Required inputs must be an exact, accepted, frozen set from completed
     * declared predecessors. A host-supplied accepted ID set makes a manifest's
     * self-reported ACCEPTED value insufficient for admission.
     */
    fun admitStage(
        graph: StageGraph,
        stageRunId: StageRunId,
        hostAcceptedArtifactIds: Set<ArtifactId>
    ): StageGateDecision {
        val valid = validate(graph)
        if (valid != StageGateDecision.Permit) return valid
        val stage = graph.stages.firstOrNull { it.correlation.stageRunId == stageRunId }
            ?: return deny("UNKNOWN_STAGE")
        if (graph.state != StageGraphState.RUNNING || stage.state != StageState.READY)
            return deny("STAGE_NOT_READY")
        val stages = graph.stages.associateBy { it.correlation.stageRunId }
        if (stage.dependencies.any { stages.getValue(it).state != StageState.ACCEPTED })
            return deny("PREREQUISITE_STAGE_NOT_ACCEPTED")
        val artifacts = graph.artifacts.associateBy { it.id }
        val inputs = stage.inputArtifacts.map { artifacts.getValue(it) }
        if (inputs.size != stage.kind.requiredInputs.size ||
            inputs.map { it.kind }.toSet() != stage.kind.requiredInputs)
            return deny("MISSING_REQUIRED_ARTIFACT")
        if (inputs.any {
                it.approval != ArtifactApproval.ACCEPTED ||
                    it.freeze != ArtifactFreeze.FROZEN ||
                    it.id !in hostAcceptedArtifactIds
            })
            return deny("ARTIFACT_NOT_ACCEPTED")
        return StageGateDecision.Permit
    }

    private fun legalStageEdge(from: StageState, to: StageState): Boolean = when (from) {
        StageState.CREATED -> to in setOf(StageState.READY, StageState.CANCELLED)
        StageState.READY -> to in setOf(StageState.RUNNING, StageState.CANCELLED)
        StageState.RUNNING -> to in setOf(StageState.ACCEPTED, StageState.REJECTED,
            StageState.FAILED, StageState.CANCELLED)
        else -> false // A retry is a new stage run with a fresh identity.
    }

    /** The sole stage state-transition entrypoint for future orchestration. */
    fun authorizeStageTransition(
        graph: StageGraph,
        stageRunId: StageRunId,
        target: StageState,
        hostAcceptedArtifactIds: Set<ArtifactId> = emptySet(),
        hostVerifiedReviewStageIds: Set<StageRunId> = emptySet()
    ): StageGateDecision {
        val valid = validate(graph)
        if (valid != StageGateDecision.Permit) return valid
        val stage = graph.stages.firstOrNull { it.correlation.stageRunId == stageRunId }
            ?: return deny("UNKNOWN_STAGE")
        if (!legalStageEdge(stage.state, target)) return deny("ILLEGAL_STAGE_TRANSITION")
        if (target == StageState.RUNNING)
            return admitStage(graph, stageRunId, hostAcceptedArtifactIds)
        if (target == StageState.ACCEPTED) {
            if (stage.kind == StageKind.REVIEW)
                return authorizeReview(graph, stageRunId, hostVerifiedReviewStageIds)
            if (stage.outputArtifacts.size != 1 ||
                stage.outputArtifacts.any { id ->
                    val artifact = graph.artifacts.firstOrNull { it.id == id }
                    artifact?.approval != ArtifactApproval.ACCEPTED ||
                        artifact.freeze != ArtifactFreeze.FROZEN ||
                        id !in hostAcceptedArtifactIds
                })
                return deny("UNACCEPTED_STAGE_OUTPUT")
        }
        if (target == StageState.REJECTED && stage.kind == StageKind.REVIEW) {
            val record = stage.review ?: return deny("INVALID_STAGE_REVIEW")
            if (record.verdict == ReviewVerdict.ACCEPT)
                return deny("INVALID_STAGE_REVIEW")
            return authorizeReview(graph, stageRunId, hostVerifiedReviewStageIds)
        }
        return StageGateDecision.Permit
    }

    /** Review is advisory unless a host has independently verified this stage ID. */
    fun authorizeReview(
        graph: StageGraph,
        reviewStageRunId: StageRunId,
        hostVerifiedReviewStageIds: Set<StageRunId>
    ): StageGateDecision {
        val valid = validate(graph)
        if (valid != StageGateDecision.Permit) return valid
        val reviewer = graph.stages.firstOrNull {
            it.correlation.stageRunId == reviewStageRunId && it.kind == StageKind.REVIEW
        } ?: return deny("INVALID_STAGE_REVIEW")
        val record = reviewer.review ?: return deny("INVALID_STAGE_REVIEW")
        val developer = graph.stages.firstOrNull {
            it.correlation.stageRunId == record.targetStageRunId &&
                it.kind == StageKind.DEVELOP
        } ?: return deny("INVALID_STAGE_REVIEW")
        val artifact = graph.artifacts.firstOrNull {
            it.id == record.implementationArtifactId
        } ?: return deny("INVALID_STAGE_REVIEW")
        if (developer.correlation.stageRunId !in reviewer.dependencies ||
            artifact.id !in reviewer.inputArtifacts ||
            artifact.producerStageRunId != developer.correlation.stageRunId ||
            artifact.sha256 != record.reviewedSha256 ||
            reviewer.agentId == null || developer.agentId == null ||
            record.requestedBy != developer.agentId ||
            record.reviewedBy != reviewer.agentId ||
            record.requestedBy == record.reviewedBy)
            return deny("INVALID_INDEPENDENT_REVIEW")
        if (reviewStageRunId !in hostVerifiedReviewStageIds)
            return deny("UNVERIFIED_REVIEW")
        return StageGateDecision.Permit
    }

    fun authorizeGraphTransition(
        graph: StageGraph,
        target: StageGraphState,
        hostAcceptedArtifactIds: Set<ArtifactId> = emptySet(),
        hostVerifiedReviewStageIds: Set<StageRunId> = emptySet()
    ): StageGateDecision {
        val valid = validate(graph)
        if (valid != StageGateDecision.Permit) return valid
        val legal = when (graph.state) {
            StageGraphState.CREATED -> target in setOf(StageGraphState.RUNNING,
                StageGraphState.CANCELLED)
            StageGraphState.RUNNING -> target in setOf(StageGraphState.SUCCEEDED,
                StageGraphState.FAILED, StageGraphState.CANCELLED)
            else -> false
        }
        if (!legal) return deny("ILLEGAL_GRAPH_TRANSITION")
        if (target == StageGraphState.SUCCEEDED) {
            val accepted = graph.stages.filter { it.state == StageState.ACCEPTED }
            if (!StageKind.entries.all { kind -> accepted.any { it.kind == kind } } ||
                accepted.size != graph.stages.size ||
                graph.artifacts.any { it.id !in hostAcceptedArtifactIds })
                return deny("GRAPH_NOT_ACCEPTED")
            for (reviewer in accepted.filter { it.kind == StageKind.REVIEW }) {
                val decision = authorizeReview(graph, reviewer.correlation.stageRunId,
                    hostVerifiedReviewStageIds)
                if (decision != StageGateDecision.Permit) return decision
            }
        }
        return StageGateDecision.Permit
    }

    /**
     * Reuses existing trusted-write ownership rules. A frozen artifact requires
     * a separately attested, exact-revision independent review; even then a
     * reviewer cannot grant a different role write access.
     */
    fun authorizeArtifactRevision(
        graph: StageGraph,
        artifactId: ArtifactId,
        actorRole: EngineeringRole,
        authorization: StageRevisionAuthorization? = null
    ): StageGateDecision {
        val valid = validate(graph)
        if (valid != StageGateDecision.Permit) return valid
        val manifest = graph.artifacts.firstOrNull { it.id == artifactId }
            ?: return deny("UNKNOWN_ARTIFACT")
        if (actorRole != manifest.ownerRole) return deny("ARTIFACT_ROLE_MISMATCH")
        var reviewedPaths: Set<String> = emptySet()
        if (manifest.freeze == ArtifactFreeze.FROZEN) {
            if (authorization == null ||
                authorization.artifactId != manifest.id ||
                authorization.expectedRevision != manifest.revision ||
                authorization.expectedSha256 != manifest.sha256)
                return deny("FROZEN_ARTIFACT")
            val reviewer = authorization.review
            val producer = graph.stages.first { it.correlation.stageRunId ==
                manifest.producerStageRunId }
            if (reviewer.requestedBy != producer.agentId?.value ||
                reviewer.reviewedBy == producer.agentId?.value ||
                reviewer.actorRole != actorRole ||
                manifest.declaredPath !in reviewer.paths ||
                ToolAuthorizationPolicy.validateIndependentReview(reviewer) !=
                    ToolAuthorizationDecision.Permit)
                return deny("INVALID_INDEPENDENT_REVIEW")
            reviewedPaths = setOf(manifest.declaredPath)
        }
        val decision = ToolAuthorizationPolicy.authorizeWrite(WriteAuthorizationFacts(
            actorRole = actorRole,
            operationKind = WriteOperationKind.EDIT,
            declaredPath = manifest.declaredPath,
            immutableArtifactPaths = if (manifest.freeze == ArtifactFreeze.FROZEN)
                setOf(manifest.declaredPath) else emptySet(),
            independentlyReviewedPaths = reviewedPaths
        ))
        return if (decision == ToolAuthorizationDecision.Permit) StageGateDecision.Permit
        else deny((decision as ToolAuthorizationDecision.Deny).deniedCode)
    }
}

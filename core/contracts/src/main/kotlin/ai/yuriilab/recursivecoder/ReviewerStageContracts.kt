package ai.yuriilab.recursivecoder

/**
 * Reviewer never receives a stage tool session or a write capability. All values
 * in a lease are supplied by the authoritative host, not by the Developer model
 * or by an untrusted caller of the reviewer use case.
 */
enum class ReviewerDisposition { ACCEPT, REJECT, NEEDS_HUMAN }
enum class ReviewerCorrectionScope { NONE, IMPLEMENTATION_ONLY, FROZEN_INPUTS }

/** Stable wire reason values; ReviewerVerdictPolicy owns allowed combinations. */
enum class ReviewerReasonCode(val wire: String) {
    OK("OK"),
    IMPLEMENTATION_DEFECT("IMPLEMENTATION_DEFECT"),
    FROZEN_ARTIFACT_CHANGE("FROZEN_ARTIFACT_CHANGE"),
    EVIDENCE_MISSING("EVIDENCE_MISSING"),
    CHECK_FAILED("CHECK_FAILED"),
    OTHER_REQUIRES_HUMAN("OTHER_REQUIRES_HUMAN")
}

/**
 * Single allowlist for the Reviewer JSON decoder and the authoritative CAS
 * finish policy. A model cannot invent codes or choose a wider repair scope.
 */
object ReviewerVerdictPolicy {
    private val allowed = linkedSetOf(
        Triple(ReviewerDisposition.ACCEPT, ReviewerReasonCode.OK.wire, ReviewerCorrectionScope.NONE),
        Triple(ReviewerDisposition.REJECT, ReviewerReasonCode.IMPLEMENTATION_DEFECT.wire,
            ReviewerCorrectionScope.IMPLEMENTATION_ONLY),
        Triple(ReviewerDisposition.NEEDS_HUMAN, ReviewerReasonCode.FROZEN_ARTIFACT_CHANGE.wire,
            ReviewerCorrectionScope.FROZEN_INPUTS),
        Triple(ReviewerDisposition.NEEDS_HUMAN, ReviewerReasonCode.EVIDENCE_MISSING.wire,
            ReviewerCorrectionScope.NONE),
        Triple(ReviewerDisposition.NEEDS_HUMAN, ReviewerReasonCode.CHECK_FAILED.wire,
            ReviewerCorrectionScope.NONE),
        Triple(ReviewerDisposition.NEEDS_HUMAN, ReviewerReasonCode.OTHER_REQUIRES_HUMAN.wire,
            ReviewerCorrectionScope.NONE)
    )

    fun isAllowed(
        verdict: ReviewerDisposition, code: String, scope: ReviewerCorrectionScope
    ): Boolean = Triple(verdict, code, scope) in allowed

    /** Preserves a stable ordering for model-facing formatting in :core:session. */
    fun allowedCombinations(): Set<Triple<ReviewerDisposition, String,
        ReviewerCorrectionScope>> = allowed.toSet()
}

/** Keys of the strict model-authored Reviewer proposal, not event metadata. */
object ReviewerProposalFields {
    const val VERDICT = "verdict"
    const val CODE = "code"
    const val SCOPE = "scope"
    const val EVIDENCE = "evidence"
}

/**
 * Trusted evidence tags written into the Reviewer prompt by
 * RunReviewerStageUseCase and parsed by the installed offline fixture.
 */
object ReviewerPromptEvidenceTags {
    const val CHECK = "verified-check"
    const val WRITE = "verified-write"
}

/** Exact evidence reference prefixes accepted at the finish gate. */
object ReviewerEvidenceReferences {
    const val CHECK_PREFIX = "check:"
    const val WRITE_PREFIX = "write:"
    const val FILE_PREFIX = "file:"
}

/** Stable, shared host reason for a definitively stale Reviewer finish lease. */
object ReviewerFinishDenialCodes {
    const val STALE_LEASE = "STALE_REVIEW_LEASE"
}

/** An Unknown outcome (or a thrown finish) may already have committed. */
sealed interface ReviewerFinishResult {
    data object Committed : ReviewerFinishResult
    /**
     * Definitive no-commit; the diagnostic code must be nonblank. Invalid
     * construction throws, so a caller observing that exception treats finish
     * as unknown (requiring reconciliation), not as a confirmed denial.
     */
    data class Denied(val code: String) : ReviewerFinishResult {
        init { require(code.isNotBlank()) }
    }
    data object Unknown : ReviewerFinishResult
}

data class ReviewerHistoryEntry(
    val correlation: StageCorrelation,
    val role: EngineeringRole,
    val state: StageState,
    val code: String? = null
)

/**
 * The host must retain the actual Developer result and verify its authenticated
 * check receipts against the active workspace and the exact manifest. A copied
 * StageValidationEvidence object is NOT proof of successful checking.
 */
data class ReviewerDeveloperHandoff(
    val developerResult: DeveloperReviewEvidence,
    val history: List<ReviewerHistoryEntry>
)

/** Snapshot of the Developer's verified handoff, without model-authored text. */
data class DeveloperReviewEvidence(
    val manifest: StageArtifactManifest,
    val changedFiles: List<ReviewerChangedFile>,
    val writeToolId: String,
    val validation: StageValidationEvidence,
    val usage: StageRoleUsage
)

data class ReviewerChangedFile(
    val path: String,
    val beforeSha256: String?,
    val afterSha256: String
)

data class ReviewerStageLease(
    val generation: String,
    val stageRunId: StageRunId,
    val graph: StageGraph,
    val acceptedIds: Set<ArtifactId>,
    val handoff: ReviewerDeveloperHandoff
)

data class ReviewerStageSnapshot(val graph: StageGraph, val acceptedIds: Set<ArtifactId>)

/** Strictly bounded and sanitized; never persist model prose or raw provider errors. */
data class ReviewerDecisionRecord(
    val correlation: StageCorrelation,
    val verdict: ReviewerDisposition,
    val reasonCode: String,
    val scope: ReviewerCorrectionScope,
    val evidenceIds: Set<String>,
    val reviewedManifestSha256: String
)

fun interface ReviewerDecisionSink {
    fun emit(decision: ReviewerDecisionRecord)
}

/**
 * beginStage must use CAS (expected graph + accepted IDs) and issue one active
 * generation, transitioning CREATED/READY -> RUNNING atomically. A thrown CAS
 * operation has UNKNOWN outcome, never a license to retry.
 *
 * verifyDeveloperEvidence MUST check real, host-retained receipt provenance,
 * exact check policy, tool/baseline identity, manifest and workspace digests,
 * not just StageCheckEvidence.passed or a model assertion. It must fail closed
 * if the checked workspace changed. The finishStage CAS must recheck all
 * frozen artifact digests, changed-file postimages, receipt provenance and
 * lease state under the SAME serialization boundary as the commit. A
 * separate pre-finish check is never sufficient: a concurrent edit between
 * that check and CAS MUST deny the commit. On denial, no review is accepted;
 * the caller must reconcile/abort the still-active lease.
 * The host also enforces ReviewerStageFinishPolicy at commit time.
 * The reviewer is never allowed to approve its own review ID or grant a write.
 */
interface ReviewerStageHost {
    fun beginStage(
        expectedGraph: StageGraph, stageRunId: StageRunId,
        expectedAcceptedIds: Set<ArtifactId>
    ): ReviewerStageLease?
    fun current(lease: ReviewerStageLease): Boolean
    fun readBytes(path: String): ByteArray?
    fun verifyDeveloperEvidence(lease: ReviewerStageLease): Boolean
    /**
     * Atomically recheck evidence and commit. Committed confirms persistence;
     * Denied(code) definitively means no review commit and reports the reason
     * (the lease may still be active). Unknown means the host cannot determine
     * whether the commit happened; return it for an indeterminate outcome
     * (e.g. a lost remote acknowledgement), and
     * throw only for an unexpected failure. Invalid Denied(code) construction
     * (including a blank code rejected by require) throws within the host call
     * and is treated as unknown by callers. Neither a thrown nor an explicit
     * unknown finish may be retried or auto-aborted.
     */
    fun finishStage(
        lease: ReviewerStageLease, updatedGraph: StageGraph,
        acceptedIds: Set<ArtifactId>, decision: ReviewerDecisionRecord
    ): ReviewerFinishResult
    fun abortStage(lease: ReviewerStageLease, terminal: StageState): ReviewerStageSnapshot?
}

/** A minimum pure gate for host CAS adapters; Permit itself is not evidence. */
object ReviewerStageFinishPolicy {
    fun permits(
        lease: ReviewerStageLease, next: StageGraph,
        acceptedIds: Set<ArtifactId>, decision: ReviewerDecisionRecord,
        hostVerifiedEvidence: Boolean
    ): StageGateDecision {
        fun deny(code: String) = StageGateDecision.Deny(code)
        if (!hostVerifiedEvidence) return deny(StageDenialCodes.UNVERIFIED_DEVELOPER_EVIDENCE)
        if (next.copy(stages = lease.graph.stages) != lease.graph ||
            acceptedIds != lease.acceptedIds || next.stages.size != lease.graph.stages.size)
            return deny(ReviewerStageContractsDiagnosticCodes.REVIEW_MUTATED_OTHER_STATE)
        val before = lease.graph.stages.singleOrNull {
            it.correlation.stageRunId == lease.stageRunId
        } ?: return deny(StageDenialCodes.UNKNOWN_STAGE)
        val after = next.stages.singleOrNull {
            it.correlation.stageRunId == lease.stageRunId
        } ?: return deny(StageDenialCodes.UNKNOWN_STAGE)
        if (before.state != StageState.RUNNING || before.kind != StageKind.REVIEW ||
            after.copy(state = before.state, review = before.review) != before ||
            next.stages.zip(lease.graph.stages).any { (a, b) ->
                a.correlation.stageRunId != b.correlation.stageRunId ||
                    (a.correlation.stageRunId != lease.stageRunId && a != b)
            }) return deny(ReviewerStageContractsDiagnosticCodes.REVIEW_MUTATED_OTHER_STATE)
        val handoff = lease.handoff.developerResult
        val manifest = handoff.manifest
        val dev = lease.graph.stages.singleOrNull {
            it.correlation.stageRunId == manifest.producerStageRunId &&
                it.kind == StageKind.DEVELOP
        } ?: return deny(StageDenialCodes.INVALID_DEVELOPER_HANDOFF)
        val record = after.review ?: return deny("MISSING_REVIEW")
        if (decision.correlation != before.correlation ||
            decision.reviewedManifestSha256 != manifest.sha256 ||
            record != StageReviewDecision(dev.correlation.stageRunId, manifest.id,
                manifest.sha256, dev.agentId ?: return deny(StageDenialCodes.MISSING_DEVELOPER_ACTOR),
                before.agentId ?: return deny(StageDenialCodes.MISSING_REVIEWER_ACTOR),
                when (decision.verdict) {
                    ReviewerDisposition.ACCEPT -> ReviewVerdict.ACCEPT
                    ReviewerDisposition.REJECT -> ReviewVerdict.REQUEST_CHANGES
                    ReviewerDisposition.NEEDS_HUMAN -> ReviewVerdict.REJECT
                })) return deny("REVIEW_IDENTITY_MISMATCH")
        val invalidCode = if (decision.verdict == ReviewerDisposition.ACCEPT)
            "INVALID_ACCEPTANCE" else "INVALID_REJECTION"
        val targetState = if (decision.verdict == ReviewerDisposition.ACCEPT)
            StageState.ACCEPTED else StageState.REJECTED
        if (!ReviewerVerdictPolicy.isAllowed(
                decision.verdict, decision.reasonCode, decision.scope
            ) || after.state != targetState)
            return deny(invalidCode)
        val checkIds = handoff.validation.requiredChecks.map { it.checkId }
        val refs = (handoff.changedFiles.map {
            ReviewerEvidenceReferences.FILE_PREFIX + it.path
        } + checkIds.map {
            ReviewerEvidenceReferences.CHECK_PREFIX + it
        } + listOf(ReviewerEvidenceReferences.WRITE_PREFIX + handoff.writeToolId)).toSet()
        if (decision.evidenceIds.isEmpty() || !refs.containsAll(decision.evidenceIds))
            return deny("UNSUPPORTED_REVIEW_EVIDENCE")
        if (decision.verdict == ReviewerDisposition.ACCEPT &&
            !decision.evidenceIds.containsAll(
                checkIds.map { ReviewerEvidenceReferences.CHECK_PREFIX + it } +
                    (ReviewerEvidenceReferences.WRITE_PREFIX + handoff.writeToolId)
            )) return deny("INCOMPLETE_REVIEW_EVIDENCE")
        if (StageGraphPolicy.validate(next) != StageGateDecision.Permit)
            return deny("INVALID_REVIEW_GRAPH")
        return StageGraphPolicy.authorizeStageTransition(
            next.copy(stages = next.stages.map {
                if (it.correlation.stageRunId == lease.stageRunId)
                    it.copy(state = StageState.RUNNING) else it
            }), lease.stageRunId, after.state, lease.acceptedIds,
            setOf(lease.stageRunId)
        )
    }
}

sealed interface ReviewerRepairResult {
    data class Planned(val graph: StageGraph) : ReviewerRepairResult
    data class Denied(val code: String) : ReviewerRepairResult
}

/**
 * Route a host-verified, implementation-only rejection to NEW stage attempts.
 * No source/spec/test write occurs here; Developer must still pass its own
 * target, frozen-input, receipt and CAS gates before applying any correction.
 */
object ReviewerRepairPlanner {
    fun plan(
        graph: StageGraph, rejectedReviewId: StageRunId,
        hostVerifiedReviewIds: Set<StageRunId>,
        verifiedDecision: ReviewerDecisionRecord,
        maxDeveloperRetries: Int,
        nextDeveloper: StageCorrelation, developerAgent: AgentId,
        nextReviewer: StageCorrelation, reviewerAgent: AgentId
    ): ReviewerRepairResult {
        fun deny(code: String) = ReviewerRepairResult.Denied(code)
        if (StageGraphPolicy.validate(graph) != StageGateDecision.Permit)
            return deny("INVALID_GRAPH")
        if (maxDeveloperRetries !in 0..32) return deny("INVALID_RETRY_LIMIT")
        val review = graph.stages.singleOrNull {
            it.correlation.stageRunId == rejectedReviewId &&
                it.kind == StageKind.REVIEW && it.supersededBy == null
        } ?: return deny("INVALID_REJECTED_REVIEW")
        val record = review.review ?: return deny(ReviewerStageContractsDiagnosticCodes.UNAUTHORIZED_REPAIR)
        if (review.state != StageState.REJECTED ||
            record.verdict != ReviewVerdict.REQUEST_CHANGES ||
            verifiedDecision.correlation != review.correlation ||
            verifiedDecision.verdict != ReviewerDisposition.REJECT ||
            verifiedDecision.scope != ReviewerCorrectionScope.IMPLEMENTATION_ONLY ||
            verifiedDecision.reasonCode != ReviewerReasonCode.IMPLEMENTATION_DEFECT.wire ||
            verifiedDecision.reviewedManifestSha256 != record.reviewedSha256 ||
            rejectedReviewId !in hostVerifiedReviewIds ||
            StageGraphPolicy.authorizeReview(graph, rejectedReviewId,
                hostVerifiedReviewIds) != StageGateDecision.Permit)
            return deny(ReviewerStageContractsDiagnosticCodes.UNAUTHORIZED_REPAIR)
        val developer = graph.stages.singleOrNull {
            it.correlation.stageRunId == record.targetStageRunId &&
                it.kind == StageKind.DEVELOP && it.state == StageState.ACCEPTED &&
                it.supersededBy == null
        } ?: return deny(StageDenialCodes.INVALID_DEVELOPER_HANDOFF)
        // Every historical Developer attempt consumes one slot; the original
        // attempt is free, additional attempts are bounded by maxDeveloperRetries.
        if (graph.stages.count { it.kind == StageKind.DEVELOP } > maxDeveloperRetries)
            return deny("RETRY_LIMIT_EXHAUSTED")
        if (nextDeveloper.stageRunId == nextReviewer.stageRunId ||
            graph.stages.any { it.correlation.stageRunId in setOf(
                nextDeveloper.stageRunId, nextReviewer.stageRunId
            ) } || developerAgent == reviewerAgent ||
            // No actor of any earlier attempt may be reused (issue #158).
            graph.stages.any { it.agentId == developerAgent || it.agentId == reviewerAgent } ||
            nextDeveloper.sessionId != graph.correlation.sessionId ||
            nextReviewer.sessionId != graph.correlation.sessionId ||
            nextDeveloper.graphId != graph.correlation.graphId ||
            nextReviewer.graphId != graph.correlation.graphId)
            return deny("INVALID_RETRY_IDENTITIES")
        val newDeveloper = developer.copy(
            correlation = nextDeveloper, state = StageState.CREATED,
            agentId = developerAgent, outputArtifacts = emptySet(), review = null,
            supersededBy = null
        )
        val newReviewer = review.copy(
            correlation = nextReviewer, state = StageState.CREATED,
            agentId = reviewerAgent,
            dependencies = (review.dependencies - developer.correlation.stageRunId) +
                nextDeveloper.stageRunId,
            inputArtifacts = review.inputArtifacts - record.implementationArtifactId,
            review = null, supersededBy = null
        )
        val next = graph.copy(stages = graph.stages.map {
            when (it.correlation.stageRunId) {
                developer.correlation.stageRunId ->
                    it.copy(supersededBy = nextDeveloper.stageRunId)
                review.correlation.stageRunId -> it.copy(supersededBy = nextReviewer.stageRunId)
                else -> it
            }
        } + newDeveloper + newReviewer)
        return if (StageGraphPolicy.validate(next) == StageGateDecision.Permit)
            ReviewerRepairResult.Planned(next)
        else deny("INVALID_REPAIR_GRAPH")
    }
}

/** Diagnostic codes emitted by ReviewerStageContracts; not shared protocol vocabulary. */
internal object ReviewerStageContractsDiagnosticCodes {
    const val REVIEW_MUTATED_OTHER_STATE = "REVIEW_MUTATED_OTHER_STATE"
    const val UNAUTHORIZED_REPAIR = "UNAUTHORIZED_REPAIR"
}

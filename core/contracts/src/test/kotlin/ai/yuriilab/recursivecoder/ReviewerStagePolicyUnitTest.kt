package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Pure policy matrices: no files, Koog, model, clock or external process. */
@Tag("unit")
class ReviewerStagePolicyUnitTest {
    private val graphId = GraphId("review-policy-77")
    private val sessionId = "review-session-77"
    private val kinds = listOf(StageKind.SDD, StageKind.ATDD, StageKind.TDD, StageKind.DEVELOP)
    private val ids = StageKind.entries.associateWith {
        StageRunId("policy-" + it.name.lowercase())
    }
    private val agents = StageKind.entries.associateWith {
        AgentId("policy-agent-" + it.name.lowercase())
    }
    private val artifacts = kinds.associateWith {
        ArtifactId("policy-artifact-" + it.name.lowercase())
    }
    private val paths = mapOf(
        StageKind.SDD to "specs/Policy77.md",
        StageKind.ATDD to "tests/acceptance/Policy77.feature",
        StageKind.TDD to "tests/unit/Policy77Test.kt",
        StageKind.DEVELOP to "src/main/Policy77Manifest.txt"
    )
    private val digests = kinds.mapIndexed { index, kind ->
        kind to (('a'.code + index).toChar().toString().repeat(64))
    }.toMap()
    private val changedPath = "src/main/Policy77.kt"

    private fun correlation(kind: StageKind): StageCorrelation =
        StageCorrelation(sessionId, graphId, ids.getValue(kind),
            "req-policy-" + kind.name.lowercase(),
            "corr-policy-" + kind.name.lowercase())

    private fun graph(): StageGraph {
        val manifests = kinds.map { kind ->
            StageArtifactManifest(artifacts.getValue(kind), requireNotNull(kind.output),
                paths.getValue(kind), digests.getValue(kind), kind.role,
                ids.getValue(kind), 1, ArtifactApproval.ACCEPTED, ArtifactFreeze.FROZEN)
        }
        val stages = StageKind.entries.map { kind ->
            val producers = kinds.filter { it.output in kind.requiredInputs }
            StageNode(correlation(kind), kind,
                if (kind == StageKind.REVIEW) StageState.RUNNING else StageState.ACCEPTED,
                agents.getValue(kind),
                dependencies = producers.map { ids.getValue(it) }.toSet(),
                inputArtifacts = producers.map { artifacts.getValue(it) }.toSet(),
                outputArtifacts = if (kind.output == null) emptySet()
                    else setOf(artifacts.getValue(kind)))
        }
        return StageGraph(GraphCorrelation(sessionId, graphId, "review-policy-corr"),
            StageGraphState.RUNNING, stages, manifests,
            StageBudget(30_000, 60_000, 0, 0))
    }

    private fun lease(): ReviewerStageLease {
        val graph = graph()
        val dev = graph.stages.single { it.kind == StageKind.DEVELOP }
        val manifest = graph.artifacts.single {
            it.id == artifacts.getValue(StageKind.DEVELOP)
        }
        val validation = StageValidationEvidence(dev.correlation,
            requireNotNull(dev.agentId),
            listOf(
                StageCheckEvidence("git_diff_check", "check-policy-diff",
                    "a".repeat(40), "policy", "manifest"),
                StageCheckEvidence("offline", "check-policy-offline",
                    "a".repeat(40), "policy", "manifest")
            ), true)
        val handoff = ReviewerDeveloperHandoff(DeveloperReviewEvidence(
            manifest,
            listOf(ReviewerChangedFile(changedPath, "0".repeat(64),
                "1".repeat(64))),
            "write-policy", validation, StageRoleUsage(1, 100, 20)
        ), emptyList())
        return ReviewerStageLease("policy-lease", ids.getValue(StageKind.REVIEW),
            graph, graph.artifacts.map { it.id }.toSet(), handoff)
    }

    private fun decision(
        verdict: ReviewerDisposition = ReviewerDisposition.ACCEPT,
        code: String = when (verdict) {
            ReviewerDisposition.ACCEPT -> "OK"
            ReviewerDisposition.REJECT -> "IMPLEMENTATION_DEFECT"
            ReviewerDisposition.NEEDS_HUMAN -> "FROZEN_ARTIFACT_CHANGE"
        },
        scope: ReviewerCorrectionScope = when (verdict) {
            ReviewerDisposition.ACCEPT -> ReviewerCorrectionScope.NONE
            ReviewerDisposition.REJECT -> ReviewerCorrectionScope.IMPLEMENTATION_ONLY
            ReviewerDisposition.NEEDS_HUMAN -> ReviewerCorrectionScope.FROZEN_INPUTS
        },
        refs: Set<String> = if (verdict == ReviewerDisposition.ACCEPT)
            setOf("check:git_diff_check", "check:offline", "write:write-policy")
            else setOf("file:" + changedPath)
    ): ReviewerDecisionRecord =
        ReviewerDecisionRecord(correlation(StageKind.REVIEW), verdict,
            code, scope, refs, digests.getValue(StageKind.DEVELOP))

    private fun next(graph: StageGraph, decision: ReviewerDecisionRecord): StageGraph =
        graph.copy(stages = graph.stages.map { stage ->
            if (stage.kind != StageKind.REVIEW) stage
            else stage.copy(
                state = if (decision.verdict == ReviewerDisposition.ACCEPT)
                    StageState.ACCEPTED else StageState.REJECTED,
                review = StageReviewDecision(ids.getValue(StageKind.DEVELOP),
                    artifacts.getValue(StageKind.DEVELOP),
                    digests.getValue(StageKind.DEVELOP),
                    agents.getValue(StageKind.DEVELOP),
                    agents.getValue(StageKind.REVIEW),
                    when (decision.verdict) {
                        ReviewerDisposition.ACCEPT -> ReviewVerdict.ACCEPT
                        ReviewerDisposition.REJECT -> ReviewVerdict.REQUEST_CHANGES
                        ReviewerDisposition.NEEDS_HUMAN -> ReviewVerdict.REJECT
                    })
            )
        })

    private fun finish(
        lease: ReviewerStageLease, decision: ReviewerDecisionRecord,
        next: StageGraph = next(lease.graph, decision),
        accepted: Set<ArtifactId> = lease.acceptedIds,
        hostVerified: Boolean = true
    ): StageGateDecision = ReviewerStageFinishPolicy.permits(
        lease, next, accepted, decision, hostVerified)

    @Test fun positiveReviewerDecisionsRequireIndependentReviewAndHostEvidence() {
        val lease = lease()
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.validate(lease.graph))
        for (verdict in ReviewerDisposition.entries) {
            val decision = decision(verdict)
            assertEquals(StageGateDecision.Permit, finish(lease, decision), verdict.name)
        }
    }

    @Test fun literalReviewerFinishDenialMatrix() {
        val lease = lease()
        val accepted = decision()
        val graph = next(lease.graph, accepted)
        val rejected = decision(ReviewerDisposition.REJECT)
        val rejectedGraph = next(lease.graph, rejected)
        val cases: List<Pair<String, () -> StageGateDecision>> = listOf(
            "UNVERIFIED_DEVELOPER_EVIDENCE" to {
                finish(lease, accepted, hostVerified = false)
            },
            "REVIEW_MUTATED_OTHER_STATE" to {
                finish(lease, accepted, graph.copy(
                    budget = graph.budget.copy(maxTokens = graph.budget.maxTokens + 1)))
            },
            "REVIEW_IDENTITY_MISMATCH" to {
                finish(lease, accepted.copy(reviewedManifestSha256 = "0".repeat(64)))
            },
            "INVALID_ACCEPTANCE" to {
                finish(lease, accepted.copy(scope = ReviewerCorrectionScope.FROZEN_INPUTS))
            },
            "INVALID_REJECTION" to {
                finish(lease, rejected.copy(scope = ReviewerCorrectionScope.NONE),
                    rejectedGraph)
            },
            "UNSUPPORTED_REVIEW_EVIDENCE" to {
                finish(lease, accepted.copy(evidenceIds = setOf("check:forged")))
            },
            "INCOMPLETE_REVIEW_EVIDENCE" to {
                finish(lease, accepted.copy(evidenceIds =
                    setOf("check:git_diff_check", "check:offline")))
            }
        )
        for ((expected, action) in cases) {
            assertEquals(StageGateDecision.Deny(expected), action(), expected)
        }
        // The pure CAS gate must also enforce verdict/code consistency independently
        // of the Reviewer's JSON decoder, including NEEDS_HUMAN.
        assertEquals(StageGateDecision.Deny("INVALID_REJECTION"),
            finish(lease, decision(ReviewerDisposition.NEEDS_HUMAN,
                code = "IMPLEMENTATION_DEFECT",
                scope = ReviewerCorrectionScope.NONE)))
    }

    private fun retryCorrelation(name: String): StageCorrelation =
        StageCorrelation(sessionId, graphId, StageRunId(name),
            "req-" + name, "corr-" + name)

    private fun plan(
        graph: StageGraph, record: ReviewerDecisionRecord,
        reviewId: StageRunId = ids.getValue(StageKind.REVIEW),
        verified: Set<StageRunId> = setOf(reviewId),
        maxRetries: Int = 1,
        dev: StageCorrelation = retryCorrelation("policy-dev-retry"),
        devAgent: AgentId = AgentId("policy-agent-dev-retry"),
        reviewer: StageCorrelation = retryCorrelation("policy-review-retry"),
        reviewerAgent: AgentId = AgentId("policy-agent-review-retry")
    ): ReviewerRepairResult = ReviewerRepairPlanner.plan(
        graph, reviewId, verified, record, maxRetries,
        dev, devAgent, reviewer, reviewerAgent)

    @Test fun literalRepairPlannerDenialMatrix() {
        val lease = lease()
        val rejected = decision(ReviewerDisposition.REJECT)
        val graph = next(lease.graph, rejected)
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.validate(graph))
        val cases: List<Pair<String, () -> ReviewerRepairResult>> = listOf(
            "UNAUTHORIZED_REPAIR" to {
                plan(graph, rejected, verified = emptySet())
            },
            "INVALID_RETRY_IDENTITIES" to {
                plan(graph, rejected, dev = correlation(StageKind.SDD))
            },
            "INVALID_RETRY_LIMIT" to {
                plan(graph, rejected, maxRetries = -1)
            },
            "RETRY_LIMIT_EXHAUSTED" to {
                plan(graph, rejected, maxRetries = 0)
            }
        )
        for ((expected, action) in cases) {
            assertEquals(ReviewerRepairResult.Denied(expected), action(), expected)
        }
        val planned = assertIs<ReviewerRepairResult.Planned>(
            plan(graph, rejected, maxRetries = 1)).graph
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.validate(planned))
        assertEquals(2, planned.stages.count { it.kind == StageKind.DEVELOP })
    }

    @Test fun historicalDeveloperAttemptsCountAgainstTheRetryLimit() {
        val lease = lease()
        val initialReject = decision(ReviewerDisposition.REJECT)
        val firstGraph = next(lease.graph, initialReject)
        val firstPlan = assertIs<ReviewerRepairResult.Planned>(
            plan(firstGraph, initialReject, maxRetries = 1)).graph
        val dev2 = firstPlan.stages.single { it.kind == StageKind.DEVELOP &&
            it.supersededBy == null }
        val review2 = firstPlan.stages.single { it.kind == StageKind.REVIEW &&
            it.supersededBy == null }
        val newManifest = StageArtifactManifest(ArtifactId("policy-artifact-develop-two"),
            StageArtifactKind.IMPLEMENTATION, "src/main/Policy77Manifest2.txt",
            "e".repeat(64), EngineeringRole.DEVELOPER, dev2.correlation.stageRunId, 1,
            ArtifactApproval.ACCEPTED, ArtifactFreeze.FROZEN)
        val secondReviewRecord = StageReviewDecision(
            dev2.correlation.stageRunId, newManifest.id, newManifest.sha256,
            requireNotNull(dev2.agentId), requireNotNull(review2.agentId),
            ReviewVerdict.REQUEST_CHANGES)
        val secondGraph = firstPlan.copy(
            artifacts = firstPlan.artifacts + newManifest,
            stages = firstPlan.stages.map {
                when (it.correlation.stageRunId) {
                    dev2.correlation.stageRunId -> it.copy(
                        state = StageState.ACCEPTED, outputArtifacts = setOf(newManifest.id))
                    review2.correlation.stageRunId -> it.copy(
                        state = StageState.REJECTED,
                        inputArtifacts = it.inputArtifacts + newManifest.id,
                        review = secondReviewRecord)
                    else -> it
                }
            })
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.validate(secondGraph))
        val secondDecision = ReviewerDecisionRecord(review2.correlation,
            ReviewerDisposition.REJECT, "IMPLEMENTATION_DEFECT",
            ReviewerCorrectionScope.IMPLEMENTATION_ONLY,
            setOf("file:" + changedPath), newManifest.sha256)
        assertEquals(ReviewerRepairResult.Denied("RETRY_LIMIT_EXHAUSTED"),
            plan(secondGraph, secondDecision,
                reviewId = review2.correlation.stageRunId,
                verified = setOf(review2.correlation.stageRunId),
                maxRetries = 1,
                dev = retryCorrelation("policy-dev-third"),
                devAgent = AgentId("policy-agent-dev-third"),
                reviewer = retryCorrelation("policy-review-third"),
                reviewerAgent = AgentId("policy-agent-review-third")))
        assertIs<ReviewerRepairResult.Planned>(
            plan(secondGraph, secondDecision,
                reviewId = review2.correlation.stageRunId,
                verified = setOf(review2.correlation.stageRunId),
                maxRetries = 2,
                dev = retryCorrelation("policy-dev-third"),
                devAgent = AgentId("policy-agent-dev-third"),
                reviewer = retryCorrelation("policy-review-third"),
                reviewerAgent = AgentId("policy-agent-review-third")))
    }
}

package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Pure deterministic five-role fixture: no model, workspace, clock or persistence. */
@Tag("unit")
class StageGraphPolicyUnitTest {
    private val graphId = GraphId("graph-72")
    private val sessionId = "session-72"
    private val stageIds = StageKind.entries.associateWith { StageRunId("stage-" + it.name.lowercase()) }
    private val agents = StageKind.entries.associateWith { AgentId("agent-" + it.name.lowercase()) }
    private val paths = mapOf(
        StageArtifactKind.REQUIREMENTS to "specs/Requirements.md",
        StageArtifactKind.ACCEPTANCE_TESTS to "tests/acceptance/Feature.md",
        StageArtifactKind.UNIT_TESTS to "tests/unit/FeatureTest.kt",
        StageArtifactKind.IMPLEMENTATION to "src/main/Feature.kt"
    )
    private val artifacts = StageArtifactKind.entries.associateWith {
        ArtifactId("artifact-" + it.name.lowercase())
    }
    private val owners = mapOf(
        StageArtifactKind.REQUIREMENTS to StageKind.SDD,
        StageArtifactKind.ACCEPTANCE_TESTS to StageKind.ATDD,
        StageArtifactKind.UNIT_TESTS to StageKind.TDD,
        StageArtifactKind.IMPLEMENTATION to StageKind.DEVELOP
    )
    private val digests = StageArtifactKind.entries.mapIndexed { index, kind ->
        kind to (('a'.code + index).toChar().toString().repeat(64))
    }.toMap()

    private fun fixture(): StageGraph {
        val manifests = StageArtifactKind.entries.map { kind ->
            val owner = owners.getValue(kind)
            StageArtifactManifest(
                id = artifacts.getValue(kind), kind = kind, declaredPath = paths.getValue(kind),
                sha256 = digests.getValue(kind), ownerRole = owner.role,
                producerStageRunId = stageIds.getValue(owner), revision = 1,
                approval = ArtifactApproval.ACCEPTED, freeze = ArtifactFreeze.FROZEN
            )
        }
        val stages = StageKind.entries.map { kind ->
            val output = kind.output
            StageNode(
                correlation = StageCorrelation(sessionId, graphId, stageIds.getValue(kind),
                    "req-" + kind.name.lowercase(), "corr-" + kind.name.lowercase()),
                kind = kind, state = StageState.ACCEPTED, agentId = agents.getValue(kind),
                dependencies = kind.requiredInputs.map { stageIds.getValue(owners.getValue(it)) }.toSet(),
                inputArtifacts = kind.requiredInputs.map { artifacts.getValue(it) }.toSet(),
                outputArtifacts = if (output == null) emptySet()
                    else setOf(artifacts.getValue(output)),
                review = if (kind == StageKind.REVIEW) StageReviewDecision(
                    targetStageRunId = stageIds.getValue(StageKind.DEVELOP),
                    implementationArtifactId = artifacts.getValue(StageArtifactKind.IMPLEMENTATION),
                    reviewedSha256 = digests.getValue(StageArtifactKind.IMPLEMENTATION),
                    requestedBy = agents.getValue(StageKind.DEVELOP),
                    reviewedBy = agents.getValue(StageKind.REVIEW),
                    verdict = ReviewVerdict.ACCEPT
                ) else null
            )
        }
        return StageGraph(
            correlation = GraphCorrelation(sessionId, graphId, "workflow-corr"),
            state = StageGraphState.RUNNING, stages = stages, artifacts = manifests,
            budget = StageBudget(100_000, 60_000, 4, 8)
        )
    }

    private fun acceptedIds(): Set<ArtifactId> = artifacts.values.toSet()
    private fun verifiedReviews(): Set<StageRunId> = setOf(stageIds.getValue(StageKind.REVIEW))
    private fun StageGraph.replaceNode(kind: StageKind, f: (StageNode) -> StageNode): StageGraph =
        copy(stages = stages.map { if (it.kind == kind) f(it) else it })
    private fun StageGraph.replaceArtifact(
        kind: StageArtifactKind, f: (StageArtifactManifest) -> StageArtifactManifest
    ): StageGraph = copy(artifacts = artifacts.map { if (it.kind == kind) f(it) else it })
    private fun code(decision: StageGateDecision): String? =
        (decision as? StageGateDecision.Deny)?.code

    @Test
    fun fiveIndependentRolesCanCompleteWithHostEvidence() {
        val graph = fixture()
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.validate(graph))
        for (kind in StageKind.entries) {
            val awaiting = graph.replaceNode(kind) {
                it.copy(state = StageState.READY,
                    review = if (kind == StageKind.REVIEW) null else it.review)
            }
            assertEquals(StageGateDecision.Permit, StageGraphPolicy.admitStage(
                awaiting, stageIds.getValue(kind), acceptedIds()), kind.name)
        }
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.authorizeReview(
            graph, stageIds.getValue(StageKind.REVIEW), verifiedReviews()))
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.authorizeGraphTransition(
            graph, StageGraphState.SUCCEEDED, acceptedIds(), verifiedReviews()))
    }

    @TestFactory
    fun stageTransitionMatrix(): List<DynamicTest> {
        val legal = mapOf(
            StageState.CREATED to setOf(StageState.READY, StageState.CANCELLED),
            StageState.READY to setOf(StageState.RUNNING, StageState.CANCELLED),
            StageState.RUNNING to setOf(StageState.ACCEPTED, StageState.REJECTED,
                StageState.FAILED, StageState.CANCELLED),
            StageState.ACCEPTED to emptySet(),
            StageState.REJECTED to emptySet(),
            StageState.FAILED to emptySet(),
            StageState.CANCELLED to emptySet()
        )
        return StageState.entries.flatMap { from ->
            StageState.entries.map { to ->
                DynamicTest.dynamicTest("$from -> $to") {
                    val graph = fixture().replaceNode(StageKind.SDD) { it.copy(state = from) }
                    val decision = StageGraphPolicy.authorizeStageTransition(
                        graph, stageIds.getValue(StageKind.SDD), to, acceptedIds(),
                        verifiedReviews())
                    assertEquals(to in legal.getValue(from), decision == StageGateDecision.Permit)
                }
            }
        }
    }

    @TestFactory
    fun graphTransitionMatrix(): List<DynamicTest> {
        val legal = mapOf(
            StageGraphState.CREATED to setOf(StageGraphState.RUNNING, StageGraphState.CANCELLED),
            StageGraphState.RUNNING to setOf(StageGraphState.SUCCEEDED, StageGraphState.FAILED,
                StageGraphState.CANCELLED),
            StageGraphState.SUCCEEDED to emptySet(),
            StageGraphState.FAILED to emptySet(),
            StageGraphState.CANCELLED to emptySet()
        )
        return StageGraphState.entries.flatMap { from ->
            StageGraphState.entries.map { to ->
                DynamicTest.dynamicTest("$from -> $to") {
                    val graph = fixture().copy(state = from)
                    val decision = StageGraphPolicy.authorizeGraphTransition(
                        graph, to, acceptedIds(), verifiedReviews())
                    assertEquals(to in legal.getValue(from), decision == StageGateDecision.Permit)
                }
            }
        }
    }

    @TestFactory
    fun prerequisiteGateNegativeCases(): List<DynamicTest> {
        val target = stageIds.getValue(StageKind.ATDD)
        val base = fixture().replaceNode(StageKind.ATDD) { it.copy(state = StageState.READY) }
        val missing = base.replaceNode(StageKind.ATDD) { it.copy(inputArtifacts = emptySet()) }
        val pending = base.replaceNode(StageKind.SDD) { it.copy(state = StageState.RUNNING) }
        val notReady = base.replaceNode(StageKind.ATDD) { it.copy(state = StageState.CREATED) }
        val noActor = base.replaceNode(StageKind.ATDD) { it.copy(agentId = null) }
        val inactiveGraph = base.copy(state = StageGraphState.CREATED)
        val cases = listOf(
            Triple("missing input", missing, "MISSING_REQUIRED_ARTIFACT"),
            Triple("unaccepted predecessor", pending, "PREREQUISITE_STAGE_NOT_ACCEPTED"),
            Triple("stage not ready", notReady, "STAGE_NOT_READY"),
            Triple("missing actor", noActor, "MISSING_STAGE_ACTOR"),
            Triple("graph is not running", inactiveGraph, "GRAPH_NOT_RUNNING")
        )
        return cases.map { (name, graph, expected) ->
            DynamicTest.dynamicTest(name) {
                assertEquals(expected, code(StageGraphPolicy.admitStage(graph, target, acceptedIds())))
            }
        } + listOf(DynamicTest.dynamicTest("host acceptance is required") {
            assertEquals("ARTIFACT_NOT_ACCEPTED",
                code(StageGraphPolicy.admitStage(base, target, emptySet())))
        })
    }

    @TestFactory
    fun malformedAndCrossCorrelatedSnapshotsFailClosed(): List<DynamicTest> {
        val base = fixture()
        val cases = listOf(
            "duplicate stage id" to base.copy(stages = base.stages + base.stages.first()),
            "duplicate artifact id" to base.copy(artifacts = base.artifacts + base.artifacts.first()),
            "duplicate declared path" to base.replaceArtifact(StageArtifactKind.ACCEPTANCE_TESTS) {
                it.copy(declaredPath = base.artifacts.first().declaredPath)
            },
            "shared agent across roles" to base.replaceNode(StageKind.ATDD) {
                it.copy(agentId = agents.getValue(StageKind.SDD))
            },
            "invalid supersession" to base.replaceNode(StageKind.SDD) {
                it.copy(supersededBy = stageIds.getValue(StageKind.REVIEW))
            },
            "stage session mismatch" to base.replaceNode(StageKind.SDD) {
                it.copy(correlation = it.correlation.copy(sessionId = "another-session"))
            },
            "stage graph mismatch" to base.replaceNode(StageKind.SDD) {
                it.copy(correlation = it.correlation.copy(graphId = GraphId("other-graph")))
            },
            "unknown dependency" to base.replaceNode(StageKind.ATDD) {
                it.copy(dependencies = it.dependencies + StageRunId("missing-stage"))
            },
            "cycle" to base.replaceNode(StageKind.SDD) {
                it.copy(dependencies = setOf(stageIds.getValue(StageKind.REVIEW)))
            },
            "wrong artifact owner" to base.replaceArtifact(StageArtifactKind.REQUIREMENTS) {
                it.copy(ownerRole = EngineeringRole.DEVELOPER)
            },
            "invalid digest" to base.replaceArtifact(StageArtifactKind.REQUIREMENTS) {
                it.copy(sha256 = "not-a-hash")
            },
            "accepted but unfrozen" to base.replaceArtifact(StageArtifactKind.REQUIREMENTS) {
                it.copy(freeze = ArtifactFreeze.EDITABLE)
            },
            "untrusted input producer" to base.replaceNode(StageKind.ATDD) {
                it.copy(dependencies = emptySet())
            },
            "child with unknown parent" to base.copy(children = listOf(
                ChildGraphReference(GraphId("child-1"), StageRunId("unknown-parent"),
                    emptySet(), 1, StageBudget(1, 1, 0, 0))
            )),
            "budget overrun metadata" to base.copy(children = listOf(
                ChildGraphReference(GraphId("child-1"), stageIds.getValue(StageKind.ATDD),
                    emptySet(), 1, StageBudget(200_000, 1, 0, 0))
            ))
        )
        val expected = listOf(
            "DUPLICATE_GRAPH_ID", "DUPLICATE_GRAPH_ID",
            "DUPLICATE_ARTIFACT_PATH", "SHARED_ROLE_ACTOR",
            "INVALID_STAGE_SUPERSESSION",
            "INVALID_STAGE_CORRELATION", "INVALID_STAGE_CORRELATION",
            "INVALID_STAGE_DEPENDENCY", "CYCLIC_STAGE_DEPENDENCY",
            "INVALID_ARTIFACT_MANIFEST", "INVALID_ARTIFACT_MANIFEST",
            "UNACCEPTED_STAGE_OUTPUT", "INVALID_INPUT_PROVENANCE",
            "INVALID_CHILD_REFERENCE", "INVALID_CHILD_REFERENCE"
        )
        return cases.zip(expected).map { (case, expectedCode) ->
            val (label, graph) = case
            DynamicTest.dynamicTest(label) {
                assertEquals(expectedCode, code(StageGraphPolicy.validate(graph)), label)
            }
        }
    }

    @Test
    fun reviewerCannotSelfApproveOrApproveChangedDigest() {
        val graph = fixture()
        val reviewId = stageIds.getValue(StageKind.REVIEW)
        assertEquals("UNVERIFIED_REVIEW",
            code(StageGraphPolicy.authorizeReview(graph, reviewId, emptySet())))
        val changed = graph.replaceNode(StageKind.REVIEW) {
            it.copy(review = it.review!!.copy(reviewedSha256 = "e".repeat(64)))
        }
        assertEquals("INVALID_INDEPENDENT_REVIEW",
            code(StageGraphPolicy.authorizeReview(changed, reviewId, verifiedReviews())))
        val selfReviewed = graph.replaceNode(StageKind.REVIEW) {
            it.copy(review = it.review!!.copy(reviewedBy = agents.getValue(StageKind.DEVELOP)))
        }
        assertEquals("INVALID_INDEPENDENT_REVIEW",
            code(StageGraphPolicy.authorizeReview(selfReviewed, reviewId, verifiedReviews())))
        val nonAccepting = graph.replaceNode(StageKind.REVIEW) {
            it.copy(state = StageState.RUNNING,
                review = it.review!!.copy(verdict = ReviewVerdict.REQUEST_CHANGES))
        }
        assertEquals("INVALID_STAGE_REVIEW", code(StageGraphPolicy.authorizeStageTransition(
            nonAccepting, reviewId, StageState.ACCEPTED, acceptedIds(), verifiedReviews())))
    }

    @Test
    fun completionRequiresBothHostArtifactApprovalAndIndependentReview() {
        val graph = fixture()
        assertEquals("GRAPH_NOT_ACCEPTED",
            code(StageGraphPolicy.authorizeGraphTransition(
                graph, StageGraphState.SUCCEEDED, emptySet(), verifiedReviews())))
        assertEquals("UNVERIFIED_REVIEW",
            code(StageGraphPolicy.authorizeGraphTransition(
                graph, StageGraphState.SUCCEEDED, acceptedIds(), emptySet())))
        val noReviewer = graph.replaceNode(StageKind.REVIEW) {
            it.copy(state = StageState.RUNNING)
        }
        assertEquals("GRAPH_NOT_ACCEPTED", code(StageGraphPolicy.authorizeGraphTransition(
            noReviewer, StageGraphState.SUCCEEDED, acceptedIds(), verifiedReviews())))
    }

    @Test
    fun frozenArtifactsRequireRevisionPinnedIndependentReviewWithoutRoleEscalation() {
        val graph = fixture()
        val manifest = graph.artifacts.first { it.kind == StageArtifactKind.REQUIREMENTS }
        assertEquals("FROZEN_ARTIFACT", code(StageGraphPolicy.authorizeArtifactRevision(
            graph, manifest.id, EngineeringRole.SDD)))
        assertEquals("ARTIFACT_ROLE_MISMATCH", code(StageGraphPolicy.authorizeArtifactRevision(
            graph, manifest.id, EngineeringRole.DEVELOPER)))
        val reviewer = ReviewedChangeFacts(
            changeRequestId = "change-1",
            requestedBy = agents.getValue(StageKind.SDD).value,
            reviewedBy = agents.getValue(StageKind.REVIEW).value,
            actorRole = EngineeringRole.SDD, reviewedRole = EngineeringRole.SDD,
            paths = setOf(manifest.declaredPath),
            approvedArtifactPaths = setOf(manifest.declaredPath)
        )
        val auth = StageRevisionAuthorization(
            manifest.id, manifest.sha256, manifest.revision, reviewer)
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.authorizeArtifactRevision(
            graph, manifest.id, EngineeringRole.SDD, auth))
        assertEquals("FROZEN_ARTIFACT", code(StageGraphPolicy.authorizeArtifactRevision(
            graph, manifest.id, EngineeringRole.SDD,
            auth.copy(expectedRevision = manifest.revision + 1))))
        assertEquals("INVALID_INDEPENDENT_REVIEW", code(
            StageGraphPolicy.authorizeArtifactRevision(graph, manifest.id,
                EngineeringRole.SDD, auth.copy(review = reviewer.copy(
                    reviewedBy = reviewer.requestedBy)))))
        assertEquals("INVALID_INDEPENDENT_REVIEW", code(
            StageGraphPolicy.authorizeArtifactRevision(graph, manifest.id,
                EngineeringRole.SDD, auth.copy(review = reviewer.copy(
                    approvedArtifactPaths = emptySet())))))
    }


    @Test
    fun rejectedReviewerMayBeSupersededByIndependentAcceptedRetry() {
        val base = fixture()
        val previous = base.stages.last()
        val newId = StageRunId("review-retry")
        val newAgent = AgentId("review-agent-retry")
        val rejected = previous.copy(
            state = StageState.REJECTED, supersededBy = newId,
            review = previous.review!!.copy(verdict = ReviewVerdict.REJECT))
        val retried = previous.copy(
            correlation = previous.correlation.copy(stageRunId = newId,
                requestId = "review-retry-request", correlationId = "review-retry-corr"),
            agentId = newAgent, review = previous.review!!.copy(reviewedBy = newAgent))
        val graph = base.copy(stages = base.stages.dropLast(1) + rejected + retried)
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.validate(graph))
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.authorizeGraphTransition(
            graph, StageGraphState.SUCCEEDED, acceptedIds(), setOf(newId)))
        assertEquals("UNVERIFIED_REVIEW", code(StageGraphPolicy.authorizeGraphTransition(
            graph, StageGraphState.SUCCEEDED, acceptedIds(), verifiedReviews())))
    }

    @Test
    fun failedDeveloperRetryRequiresNewImplementationAndUpdatedReviewerInputs() {
        val base = fixture()
        val original = base.stages.first { it.kind == StageKind.DEVELOP }
        val originalManifest = base.artifacts.first {
            it.kind == StageArtifactKind.IMPLEMENTATION
        }
        val retryId = StageRunId("develop-retry")
        val retryArtifactId = ArtifactId("implementation-retry")
        val retriedManifest = originalManifest.copy(
            id = retryArtifactId, declaredPath = "src/main/FeatureRetry.kt",
            sha256 = "f".repeat(64), producerStageRunId = retryId)
        val oldNode = original.copy(state = StageState.FAILED,
            supersededBy = retryId)
        val retried = original.copy(
            correlation = original.correlation.copy(stageRunId = retryId,
                requestId = "develop-retry-request", correlationId = "develop-retry-corr"),
            outputArtifacts = setOf(retryArtifactId))
        val newReviewer = base.stages.last().let {
            it.copy(
                dependencies = (it.dependencies - original.correlation.stageRunId) + retryId,
                inputArtifacts = (it.inputArtifacts - originalManifest.id) + retryArtifactId,
                review = it.review!!.copy(targetStageRunId = retryId,
                    implementationArtifactId = retryArtifactId,
                    reviewedSha256 = retriedManifest.sha256))
        }
        val graph = base.copy(
            stages = base.stages.dropLast(2) + oldNode + retried + newReviewer,
            artifacts = base.artifacts.map {
                if (it.id == originalManifest.id)
                    it.copy(approval = ArtifactApproval.DRAFT, freeze = ArtifactFreeze.EDITABLE)
                else it
            } + retriedManifest)
        val activeAcceptance = acceptedIds() - originalManifest.id + retryArtifactId
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.validate(graph))
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.authorizeGraphTransition(
            graph, StageGraphState.SUCCEEDED, activeAcceptance, verifiedReviews()))
        val staleReview = graph.copy(stages = graph.stages.map {
            if (it.kind == StageKind.REVIEW) it.copy(
                inputArtifacts = (it.inputArtifacts - retryArtifactId) + originalManifest.id,
                dependencies = (it.dependencies - retryId) + original.correlation.stageRunId,
                review = it.review!!.copy(targetStageRunId = original.correlation.stageRunId,
                    implementationArtifactId = originalManifest.id,
                    reviewedSha256 = originalManifest.sha256))
            else it
        })
        assertEquals("GRAPH_NOT_ACCEPTED", code(StageGraphPolicy.authorizeGraphTransition(
            staleReview, StageGraphState.SUCCEEDED, activeAcceptance, verifiedReviews())))
    }

    @Test
    fun rejectedReviewRequiresItsOwnHostVerifiedEvidence() {
        val id = stageIds.getValue(StageKind.REVIEW)
        for (verdict in listOf(ReviewVerdict.REQUEST_CHANGES, ReviewVerdict.REJECT)) {
            val graph = fixture().replaceNode(StageKind.REVIEW) {
                it.copy(state = StageState.RUNNING, review = it.review!!.copy(verdict = verdict))
            }
            assertEquals("UNVERIFIED_REVIEW", code(StageGraphPolicy.authorizeStageTransition(
                graph, id, StageState.REJECTED, acceptedIds(), emptySet())), verdict.name)
            assertEquals(StageGateDecision.Permit, StageGraphPolicy.authorizeStageTransition(
                graph, id, StageState.REJECTED, acceptedIds(), verifiedReviews()), verdict.name)
        }
    }

    @Test
    fun childMetadataAcceptsMaximumDepthButRejectsItsOverflow() {
        val base = fixture()
        val child = ChildGraphReference(
            GraphId("nested-graph"), stageIds.getValue(StageKind.SDD),
            setOf(stageIds.getValue(StageKind.ATDD)),
            depth = 4, budget = StageBudget(10, 10, 0, 0))
        assertEquals(StageGateDecision.Permit,
            StageGraphPolicy.validate(base.copy(children = listOf(child))))
        assertEquals("INVALID_CHILD_REFERENCE", code(StageGraphPolicy.validate(
            base.copy(children = listOf(child.copy(depth = 5))))))
    }

    @Test
    fun transitionAndRevisionRejectInactiveGraphs() {
        val graph = fixture().copy(state = StageGraphState.CREATED)
        val sdd = stageIds.getValue(StageKind.SDD)
        val stageReady = graph.replaceNode(StageKind.SDD) {
            it.copy(state = StageState.READY)
        }
        assertEquals("GRAPH_NOT_RUNNING", code(StageGraphPolicy.admitStage(
            stageReady, sdd, acceptedIds())))
        assertEquals("GRAPH_NOT_RUNNING", code(StageGraphPolicy.authorizeStageTransition(
            stageReady, sdd, StageState.RUNNING, acceptedIds(), verifiedReviews())))
        assertEquals("GRAPH_NOT_RUNNING", code(StageGraphPolicy.authorizeArtifactRevision(
            graph, artifacts.getValue(StageArtifactKind.REQUIREMENTS), EngineeringRole.SDD)))
    }

    @Test
    fun invalidIdentifierNeverBecomesAnAuthorityToken() {
        assertFailsWith<IllegalArgumentException> { GraphId("bad id") }
        assertFailsWith<IllegalArgumentException> { ArtifactId("../escape") }
        assertFailsWith<IllegalArgumentException> { StageRunId("") }
        assertFailsWith<IllegalArgumentException> { AgentId("caller\nclaimed") }
    }
}

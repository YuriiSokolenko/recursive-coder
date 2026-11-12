package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
        val cases = listOf(
            Triple("missing input", missing, "MISSING_REQUIRED_ARTIFACT"),
            Triple("unaccepted predecessor", pending, "PREREQUISITE_STAGE_NOT_ACCEPTED"),
            Triple("stage not ready", notReady, "STAGE_NOT_READY")
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
        return cases.map { (label, graph) ->
            DynamicTest.dynamicTest(label) {
                assertTrue(StageGraphPolicy.validate(graph) is StageGateDecision.Deny, label)
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
    fun invalidIdentifierNeverBecomesAnAuthorityToken() {
        assertFailsWith<IllegalArgumentException> { GraphId("bad id") }
        assertFailsWith<IllegalArgumentException> { ArtifactId("../escape") }
        assertFailsWith<IllegalArgumentException> { StageRunId("") }
        assertFailsWith<IllegalArgumentException> { AgentId("caller\nclaimed") }
    }
}

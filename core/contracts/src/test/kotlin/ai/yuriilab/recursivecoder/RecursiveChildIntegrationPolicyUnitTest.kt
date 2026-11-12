package ai.yuriilab.recursivecoder

import java.time.Instant
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Pure reconciliation matrix, including malicious/stale host-fact fixtures. */
@Tag("unit")
class RecursiveChildIntegrationPolicyUnitTest {
    private val rootId = GraphId("integration-root")
    private val a = GraphId("integration-a")
    private val b = GraphId("integration-b")
    private val root = RecursiveNode(rootId, StageRunId("integration-root-stage"),
        null, null, null, StageKind.SDD, "root-task", emptySet(), 0,
        setOf("specs/A.md", "specs/B.md"), setOf("specs/Locked.md"),
        emptySet(), RecursiveUsage(100, 10), deadline = Instant.parse("2027-01-01T00:00:00Z"),
        state = RecursiveNodeState.RUNNING)
    private val first = child(a, "a", "specs/A.md")
    private val second = child(b, "b", "specs/B.md", setOf(a))

    private fun child(id: GraphId, name: String, path: String,
        dependencies: Set<GraphId> = emptySet()) = RecursiveNode(
        id, StageRunId("stage-$name"), rootId, root.stageRunId,
        "request-$name", StageKind.SDD, "task-$name", dependencies, 1,
        setOf(path), root.frozenPaths, emptySet(), RecursiveUsage(30, 3),
        deadline = root.deadline, state = RecursiveNodeState.SUCCEEDED
    )

    private fun receipt(node: RecursiveNode, name: String, path: String,
        digest: String = "a".repeat(64)): VerifiedChildArtifact {
        val manifest = StageArtifactManifest(ArtifactId("artifact-$name"),
            StageArtifactKind.REQUIREMENTS, path, digest, EngineeringRole.SDD,
            node.stageRunId, 1, ArtifactApproval.ACCEPTED, ArtifactFreeze.FROZEN)
        return VerifiedChildArtifact(node.graphId, manifest, digest)
    }

    private val ra = receipt(first, "a", "specs/A.md")
    private val rb = receipt(second, "b", "specs/B.md")
    private val accepted = setOf(ra.manifest.id, rb.manifest.id)
    private val snapshot get() = RecursiveSnapshot(listOf(root, second, first))
    private fun decide(
        snapshot: RecursiveSnapshot = this.snapshot,
        receipts: Map<GraphId, List<VerifiedChildArtifact>> =
            mapOf(b to listOf(rb), a to listOf(ra)),
        ids: Set<ArtifactId> = accepted,
        existing: List<StageArtifactManifest> = emptyList()
    ) = RecursiveChildIntegrationPolicy.integrate(snapshot, rootId, receipts, ids, existing)

    private fun denied(code: String, decision: ChildIntegrationDecision) {
        assertEquals(code, assertIs<ChildIntegrationDecision.Denied>(decision).code)
    }

    @Test fun dependencyOrderIsDeterministicAcrossInsertionOrders() {
        val result = assertIs<ChildIntegrationDecision.Accepted>(decide()).results
        assertEquals(listOf(a, b), result.childOrder)
        assertEquals(listOf(ra, rb), result.artifacts)
        assertEquals(result, assertIs<ChildIntegrationDecision.Accepted>(decide(
            snapshot = RecursiveSnapshot(listOf(root, first, second)),
            receipts = mapOf(a to listOf(ra), b to listOf(rb)))).results)
    }

    @Test fun failedOrUnreportedChildNeverSatisfiesParentGate() {
        denied("MISSING_CHILD_EVIDENCE", decide(receipts = mapOf(a to listOf(ra))))
        denied("CHILD_NOT_SUCCEEDED", decide(snapshot = RecursiveSnapshot(
            listOf(root, first, second.copy(state = RecursiveNodeState.FAILED)))))
        denied("CHILD_ARTIFACT_MISSING", decide(receipts =
            mapOf(a to listOf(ra), b to emptyList())))
        denied("INVALID_CHILD_ARTIFACT_COUNT", decide(receipts =
            mapOf(a to listOf(ra, ra), b to listOf(rb))))
    }

    @Test fun acceptanceAndDigestMustBeIndependentlyConfirmed() {
        denied("CHILD_ARTIFACT_UNACCEPTED", decide(ids = setOf(ra.manifest.id)))
        denied("CHILD_ARTIFACT_UNACCEPTED", decide(receipts = mapOf(
            a to listOf(ra.copy(manifest = ra.manifest.copy(
                approval = ArtifactApproval.REJECTED))), b to listOf(rb))))
        denied("CHILD_ARTIFACT_UNACCEPTED", decide(receipts = mapOf(
            a to listOf(ra.copy(manifest = ra.manifest.copy(
                freeze = ArtifactFreeze.EDITABLE))), b to listOf(rb))))
        denied("CHILD_ARTIFACT_DIGEST_MISMATCH", decide(receipts = mapOf(
            a to listOf(ra.copy(observedSha256 = "b".repeat(64))), b to listOf(rb))))
        denied("CHILD_ARTIFACT_UNACCEPTED", decide(receipts = mapOf(
            a to listOf(ra.copy(manifest = ra.manifest.copy(
                producerStageRunId = second.stageRunId))), b to listOf(rb))))
    }

    @Test fun ownershipImmutabilityAndPathConflictsAreChecked() {
        denied("CHILD_ARTIFACT_SCOPE_DENIED", decide(receipts = mapOf(
            a to listOf(ra.copy(manifest = ra.manifest.copy(
                declaredPath = "specs/Locked.md"))), b to listOf(rb))))
        denied("CHILD_ARTIFACT_PATH_CONFLICT", decide(existing =
            listOf(rb.manifest)))
        denied("CHILD_ARTIFACT_ID_CONFLICT", decide(receipts = mapOf(
            a to listOf(ra), b to listOf(rb.copy(manifest = rb.manifest.copy(
                id = ra.manifest.id))))))
        denied("CHILD_ARTIFACT_SCOPE_DENIED", decide(receipts = mapOf(
            a to listOf(ra.copy(manifest = ra.manifest.copy(
                declaredPath = "src/main/Illegal.kt"))), b to listOf(rb))))
    }

    @Test fun cycleAndReviewerEmptyReceiptsHaveExplicitOutcomes() {
        denied("CYCLIC_CHILD_DEPENDENCY", decide(snapshot = RecursiveSnapshot(
            listOf(root, first.copy(dependencies = setOf(b)), second))))
        val reviewerRoot = root.copy(origin = StageKind.REVIEW, writablePaths = emptySet())
        val reviewerChild = first.copy(origin = StageKind.REVIEW, writablePaths = emptySet(),
            dependencies = emptySet())
        assertIs<ChildIntegrationDecision.Accepted>(
            RecursiveChildIntegrationPolicy.integrate(
                RecursiveSnapshot(listOf(reviewerRoot, reviewerChild)), rootId,
                mapOf(a to emptyList()), emptySet()))
    }
}

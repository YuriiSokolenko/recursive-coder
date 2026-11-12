package ai.yuriilab.recursivecoder

/**
 * A digest measured by the trusted host against the current workspace, not a value
 * copied from an agent's proposed manifest. The host also separately attests the
 * accepted artifact ID set. Neither a digest nor an in-memory permit grants writes.
 */
data class VerifiedChildArtifact(
    val childGraphId: GraphId,
    val manifest: StageArtifactManifest,
    val observedSha256: String
)

data class IntegratedChildResults(
    val parentGraphId: GraphId,
    /** Dependency-topological order with a lexical tie-break, independent of admission order. */
    val childOrder: List<GraphId>,
    val artifacts: List<VerifiedChildArtifact>
)

sealed interface ChildIntegrationDecision {
    data class Accepted(val results: IntegratedChildResults) : ChildIntegrationDecision
    data class Denied(val code: String) : ChildIntegrationDecision
}

/**
 * Pure fail-closed reconciliation of accepted, frozen child manifests. This does not
 * accept an agent's own claims of approval or trust a successful execution outcome
 * as proof that an artifact exists. The caller must obtain receipt inputs from the
 * authoritative host and check them again at the parent completion boundary.
 */
object RecursiveChildIntegrationPolicy {
    private val sha256 = Regex("[0-9a-f]{64}")
    private fun deny(code: String) = ChildIntegrationDecision.Denied(code)

    fun integrate(
        snapshot: RecursiveSnapshot,
        parentId: GraphId,
        receiptsByChild: Map<GraphId, List<VerifiedChildArtifact>>,
        hostAcceptedIds: Set<ArtifactId>,
        reservedParentArtifacts: List<StageArtifactManifest> = emptyList()
    ): ChildIntegrationDecision {
        val parent = snapshot.node(parentId) ?: return deny("UNKNOWN_PARENT")
        if (parent.state != RecursiveNodeState.RUNNING)
            return deny("PARENT_NOT_RUNNING")
        val children = snapshot.nodes.filter { it.parentGraphId == parentId }
        val byId = children.associateBy { it.graphId }
        if (receiptsByChild.keys != byId.keys) return deny("MISSING_CHILD_EVIDENCE")
        if (children.any { it.state != RecursiveNodeState.SUCCEEDED })
            return deny("CHILD_NOT_SUCCEEDED")

        // Kahn ordering restricted to direct siblings. Invalid/cyclic edges never
        // produce a partially accepted batch.
        val order = mutableListOf<GraphId>()
        val remaining = children.map { it.graphId }.toMutableSet()
        while (remaining.isNotEmpty()) {
            val next = remaining.filter { id ->
                byId.getValue(id).dependencies.all { it in order }
            }.minByOrNull { it.value } ?: return deny("CYCLIC_CHILD_DEPENDENCY")
            remaining.remove(next)
            order += next
        }

        val usedPaths = reservedParentArtifacts.map { it.declaredPath }.toMutableSet()
        val usedIds = reservedParentArtifacts.map { it.id }.toMutableSet()
        if (usedPaths.size != reservedParentArtifacts.size ||
            usedIds.size != reservedParentArtifacts.size)
            return deny("PARENT_ARTIFACT_CONFLICT")
        val accepted = mutableListOf<VerifiedChildArtifact>()
        for (id in order) {
            val child = byId.getValue(id)
            val receipts = receiptsByChild.getValue(id)
            if (child.origin != StageKind.REVIEW && receipts.isEmpty())
                return deny("CHILD_ARTIFACT_MISSING")
            for (receipt in receipts.sortedWith(compareBy({ it.manifest.declaredPath },
                    { it.manifest.id.value }))) {
                val manifest = receipt.manifest
                if (receipt.childGraphId != id ||
                    manifest.producerStageRunId != child.stageRunId ||
                    manifest.ownerRole != child.origin.role ||
                    manifest.kind != child.origin.output ||
                    manifest.approval != ArtifactApproval.ACCEPTED ||
                    manifest.freeze != ArtifactFreeze.FROZEN ||
                    manifest.id !in hostAcceptedIds ||
                    manifest.revision < 1)
                    return deny("CHILD_ARTIFACT_UNACCEPTED")
                if (!sha256.matches(manifest.sha256) ||
                    receipt.observedSha256 != manifest.sha256)
                    return deny("CHILD_ARTIFACT_DIGEST_MISMATCH")
                if (manifest.declaredPath !in child.writablePaths ||
                    manifest.declaredPath !in parent.writablePaths ||
                    manifest.declaredPath in parent.frozenPaths ||
                    ToolAuthorizationPolicy.authorizeWrite(WriteAuthorizationFacts(
                        child.origin.role, WriteOperationKind.CREATE,
                        manifest.declaredPath)) != ToolAuthorizationDecision.Permit)
                    return deny("CHILD_ARTIFACT_SCOPE_DENIED")
                if (!usedPaths.add(manifest.declaredPath))
                    return deny("CHILD_ARTIFACT_PATH_CONFLICT")
                if (!usedIds.add(manifest.id))
                    return deny("CHILD_ARTIFACT_ID_CONFLICT")
                accepted += receipt
            }
        }
        return ChildIntegrationDecision.Accepted(IntegratedChildResults(
            parentId, order.toList(), accepted.toList()))
    }
}

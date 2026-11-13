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
        reservedParentArtifacts: List<StageArtifactManifest> = emptyList(),
        integratedNested: Map<GraphId, IntegratedChildResults> = emptyMap()
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
        if (children.any { child -> child.dependencies.any { it !in byId } })
            return deny("UNKNOWN_CHILD_DEPENDENCY")
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
        fun addReceipt(receipt: VerifiedChildArtifact): ChildIntegrationDecision.Denied? {
            val producer = snapshot.node(receipt.childGraphId)
                ?: return ChildIntegrationDecision.Denied("UNKNOWN_CHILD_ARTIFACT")
            val manifest = receipt.manifest
            if (manifest.producerStageRunId != producer.stageRunId ||
                manifest.ownerRole != producer.origin.role ||
                manifest.kind != producer.origin.output ||
                manifest.approval != ArtifactApproval.ACCEPTED ||
                manifest.freeze != ArtifactFreeze.FROZEN ||
                manifest.id !in hostAcceptedIds ||
                manifest.revision < 1)
                return ChildIntegrationDecision.Denied("CHILD_ARTIFACT_UNACCEPTED")
            if (!sha256.matches(manifest.sha256) ||
                receipt.observedSha256 != manifest.sha256)
                return ChildIntegrationDecision.Denied("CHILD_ARTIFACT_DIGEST_MISMATCH")
            if (manifest.declaredPath !in producer.writablePaths ||
                manifest.declaredPath !in parent.writablePaths ||
                manifest.declaredPath in parent.frozenPaths ||
                ToolAuthorizationPolicy.authorizeWrite(WriteAuthorizationFacts(
                    producer.origin.role, WriteOperationKind.CREATE,
                    manifest.declaredPath)) != ToolAuthorizationDecision.Permit)
                return ChildIntegrationDecision.Denied("CHILD_ARTIFACT_SCOPE_DENIED")
            if (!usedPaths.add(manifest.declaredPath))
                return ChildIntegrationDecision.Denied("CHILD_ARTIFACT_PATH_CONFLICT")
            if (!usedIds.add(manifest.id))
                return ChildIntegrationDecision.Denied("CHILD_ARTIFACT_ID_CONFLICT")
            accepted += receipt
            return null
        }
        if (integratedNested.keys.any { it !in byId })
            return deny("UNEXPECTED_NESTED_RESULTS")
        for (id in order) {
            val child = byId.getValue(id)
            val nested = snapshot.nodes.filter { it.parentGraphId == id }
            val nestedResults = integratedNested[id]
            if (nested.isNotEmpty() && (nestedResults == null ||
                nestedResults.parentGraphId != id ||
                nestedResults.childOrder.toSet() != nested.map { it.graphId }.toSet()))
                return deny("NESTED_RESULTS_NOT_INTEGRATED")
            // Transitive outputs are included before the enclosing child's own
            // manifest; they are checked for conflicts against every sibling.
            for (receipt in nestedResults?.artifacts.orEmpty()) {
                var ancestor = snapshot.node(receipt.childGraphId)
                var belongsToChild = false
                while (ancestor?.parentGraphId != null) {
                    if (ancestor.parentGraphId == id) {
                        belongsToChild = true
                        break
                    }
                    ancestor = snapshot.node(ancestor.parentGraphId!!)
                }
                if (!belongsToChild) return deny("UNKNOWN_CHILD_ARTIFACT")
                addReceipt(receipt)?.let { return it }
            }
            val receipts = receiptsByChild.getValue(id)
            if (child.origin != StageKind.REVIEW && receipts.isEmpty())
                return deny("CHILD_ARTIFACT_MISSING")
            // An accepted stage has exactly one output manifest; Reviewer has none.
            // Never admit a second artifact smuggled under an otherwise valid stage.
            val expected = if (child.origin == StageKind.REVIEW) 0 else 1
            if (receipts.size != expected)
                return deny("INVALID_CHILD_ARTIFACT_COUNT")
            for (receipt in receipts.sortedWith(compareBy({ it.manifest.declaredPath },
                    { it.manifest.id.value }))) {
                if (receipt.childGraphId != id)
                    return deny("CHILD_ARTIFACT_UNACCEPTED")
                addReceipt(receipt)?.let { return it }
            }
        }
        return ChildIntegrationDecision.Accepted(IntegratedChildResults(
            parentId, order.toList(), accepted.toList()))
    }
}

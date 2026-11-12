package ai.yuriilab.recursivecoder

import java.time.Instant
import java.security.MessageDigest

/**
 * Host-only recursive work contracts (NOT Session Protocol v1). A proposal is never an
 * authorization, and a graph ID is never a workspace or model-tool capability.
 */
data class RecursiveRootGrant(
    val graph: StageGraph,
    val stageRunId: StageRunId,
    val taskId: String,
    val writablePaths: Set<String>,
    val frozenPaths: Set<String>,
    val allowedReadTools: Set<String> = emptySet()
)

/** A global, host-selected ceiling shared by the entire tree, not per child. */
data class RecursiveLimits(
    val maxToolCalls: Long,
    val maxTotalChildren: Int,
    val maxStageDurationMillis: Long,
    val deadline: Instant
)

data class RecursiveChildProposal(
    val requestId: String,
    val parentGraphId: GraphId,
    val parentStageRunId: StageRunId,
    val origin: StageKind,
    val taskId: String,
    val dependsOn: Set<GraphId> = emptySet(),
    val writablePaths: Set<String> = emptySet(),
    val allowedReadTools: Set<String> = emptySet(),
    val maxTokens: Long,
    val maxToolCalls: Long,
    val maxDurationMillis: Long
)

enum class RecursiveNodeState { READY, RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT }

data class RecursiveUsage(val tokens: Long = 0, val toolCalls: Long = 0)

/**
 * Host-owned snapshot. Delegated allocations are NON-REFUNDABLE, including on child
 * failure/cancellation. A parent's own charges + all direct child grants fit its grant;
 * grandchildren are counted inside their ancestor's grants, not a second time globally.
 */
data class RecursiveNode(
    val graphId: GraphId,
    val stageRunId: StageRunId,
    val parentGraphId: GraphId?,
    val parentStageRunId: StageRunId?,
    val requestId: String?,
    val origin: StageKind,
    val taskId: String,
    val dependencies: Set<GraphId>,
    val depth: Int,
    val writablePaths: Set<String>,
    val frozenPaths: Set<String>,
    val allowedReadTools: Set<String>,
    val granted: RecursiveUsage,
    val spent: RecursiveUsage = RecursiveUsage(),
    val delegated: RecursiveUsage = RecursiveUsage(),
    val deadline: Instant,
    val state: RecursiveNodeState = RecursiveNodeState.READY
)

data class RecursiveSnapshot(val nodes: List<RecursiveNode>) {
    fun node(id: GraphId): RecursiveNode? = nodes.firstOrNull { it.graphId == id }
}

sealed interface RecursiveDecision {
    data class Allowed(val child: RecursiveNode) : RecursiveDecision
    data class Denied(val code: String) : RecursiveDecision
}

object RecursiveGraphPolicy {
    /** Hard safety bound for snapshot traversal (independent of host configuration). */
    const val MAX_TOTAL_CHILDREN: Int = 512

    private fun denied(code: String): RecursiveDecision = RecursiveDecision.Denied(code)
    private fun validId(value: String): Boolean = ToolAuthorizationPolicy.validHostIdentifier(value)

    /** Stable, collision-resistant host namespace. No caller/model-selected child IDs. */
    fun childId(parentGraphId: GraphId, parentStageRunId: StageRunId, requestId: String): GraphId {
        val bytes = listOf(parentGraphId.value, parentStageRunId.value, requestId)
            .joinToString(separator = "\u0000").toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return GraphId("child-" + hash)
    }

    fun childStageId(childGraphId: GraphId): StageRunId =
        StageRunId("stage-" + childGraphId.value)

    private fun validUsage(usage: RecursiveUsage): Boolean =
        usage.tokens >= 0 && usage.toolCalls >= 0

    private fun within(granted: RecursiveUsage, spent: RecursiveUsage,
                       delegated: RecursiveUsage): Boolean =
        validUsage(granted) && validUsage(spent) && validUsage(delegated) &&
            spent.tokens <= granted.tokens && delegated.tokens <= granted.tokens - spent.tokens &&
            spent.toolCalls <= granted.toolCalls &&
            delegated.toolCalls <= granted.toolCalls - spent.toolCalls

    private fun roleScope(role: EngineeringRole, paths: Set<String>): Boolean =
        paths.all {
            ToolAuthorizationPolicy.authorizeWrite(WriteAuthorizationFacts(
                actorRole = role, operationKind = WriteOperationKind.CREATE, declaredPath = it
            )) == ToolAuthorizationDecision.Permit
        }

    private fun validReadTools(tools: Set<String>): Boolean =
        tools.all { it in ToolAuthorizationPolicy.knownReadTools }

    /** Validate untrusted/reloaded topology before any admission or execution. */
    fun validate(snapshot: RecursiveSnapshot, limits: RecursiveLimits,
                 graphBudget: StageBudget): RecursiveDecision {
        if (limits.maxToolCalls < 0 || limits.maxTotalChildren !in 0..MAX_TOTAL_CHILDREN ||
            limits.maxStageDurationMillis <= 0 ||
            graphBudget.maxTokens <= 0 || graphBudget.maxDurationMillis <= 0 ||
            graphBudget.maxDepth < 0 || graphBudget.maxChildren < 0)
            return denied("INVALID_RECURSIVE_LIMITS")
        val nodes = snapshot.nodes
        if (nodes.isEmpty() || nodes.count { it.parentGraphId == null } != 1)
            return denied("INVALID_ROOT")
        val byId = nodes.associateBy { it.graphId }
        if (byId.size != nodes.size ||
            nodes.map { it.stageRunId }.toSet().size != nodes.size)
            return denied("DUPLICATE_GRAPH_ID")
        if (nodes.size - 1 > limits.maxTotalChildren)
            return denied("GLOBAL_CHILD_LIMIT")
        val root = nodes.single { it.parentGraphId == null }
        if (root.depth != 0 || root.parentStageRunId != null || root.requestId != null ||
            root.dependencies.isNotEmpty() || root.granted.tokens > graphBudget.maxTokens ||
            root.granted.toolCalls > limits.maxToolCalls ||
            root.deadline.isAfter(limits.deadline))
            return denied("INVALID_ROOT")
        for (node in nodes) {
            if (!validId(node.taskId) || node.requestId?.let { !validId(it) } == true ||
                node.depth !in 0..graphBudget.maxDepth || !within(node.granted, node.spent,
                    node.delegated) || !validReadTools(node.allowedReadTools) ||
                !roleScope(node.origin.role, node.writablePaths) ||
                node.writablePaths.any { it in node.frozenPaths } ||
                node.frozenPaths.any { !ToolAuthorizationPolicy.validDeclaredPath(it) })
                return denied("INVALID_RECURSIVE_NODE")
            if (node.parentGraphId != null) {
                val parent = byId[node.parentGraphId]
                    ?: return denied("ORPHAN_GRAPH")
                if (node.parentStageRunId != parent.stageRunId ||
                    node.depth != parent.depth + 1 || node.origin != parent.origin ||
                    node.requestId == null ||
                    node.writablePaths.any { it !in parent.writablePaths } ||
                    node.allowedReadTools.any { it !in parent.allowedReadTools } ||
                    node.frozenPaths != parent.frozenPaths ||
                    node.deadline.isAfter(parent.deadline))
                    return denied("CHILD_AUTHORITY_ESCALATION")
                if (parent.state in setOf(RecursiveNodeState.SUCCEEDED,
                        RecursiveNodeState.FAILED, RecursiveNodeState.CANCELLED,
                        RecursiveNodeState.TIMED_OUT) &&
                    node.state in setOf(RecursiveNodeState.READY, RecursiveNodeState.RUNNING))
                    return denied("INVALID_RECURSIVE_STATE")
                if (node.dependencies.any {
                        val sibling = byId[it]
                        sibling == null || sibling.parentGraphId != node.parentGraphId
                    }) return denied("INVALID_CHILD_DEPENDENCY")
            }
        }
        // All graph parent and dependency edges must be acyclic (Kahn, no stack growth).
        val complete = mutableSetOf<GraphId>()
        while (complete.size < nodes.size) {
            val next = nodes.firstOrNull {
                it.graphId !in complete &&
                    (it.parentGraphId == null || it.parentGraphId in complete) &&
                    it.dependencies.all { dependency -> dependency in complete }
            } ?: return denied("CYCLIC_RECURSIVE_GRAPH")
            complete += next.graphId
        }
        for (parent in nodes) {
            val children = nodes.filter { it.parentGraphId == parent.graphId }
            // Addition cannot overflow: check each part against remaining capacity.
            var reservedTokens = 0L
            var reservedTools = 0L
            for (child in children) {
                if (child.granted.tokens > parent.granted.tokens - reservedTokens ||
                    child.granted.toolCalls > parent.granted.toolCalls - reservedTools)
                    return denied("INVALID_CHILD_RESERVATION")
                reservedTokens += child.granted.tokens
                reservedTools += child.granted.toolCalls
            }
            if (children.size > graphBudget.maxChildren ||
                parent.delegated.tokens != reservedTokens ||
                parent.delegated.toolCalls != reservedTools)
                return denied("INVALID_CHILD_RESERVATION")
            if (children.map { it.requestId }.toSet().size != children.size ||
                children.map { it.taskId }.toSet().size != children.size)
                return denied("REPEATED_CHILD_WORK")
        }
        return RecursiveDecision.Allowed(root)
    }

    /**
     * Pure admission: the caller must supply a trusted parent state and host time.
     * Permission scope is exact-path, monotonic and always rechecked against role policy.
     */
    fun admit(snapshot: RecursiveSnapshot, parentId: GraphId, request: RecursiveChildProposal,
              limits: RecursiveLimits, budget: StageBudget, now: Instant): RecursiveDecision {
        val validation = validate(snapshot, limits, budget)
        if (validation is RecursiveDecision.Denied) return validation
        val parent = snapshot.node(parentId) ?: return denied("UNKNOWN_PARENT")
        if (parent.state != RecursiveNodeState.RUNNING) return denied("PARENT_NOT_RUNNING")
        if (!now.isBefore(limits.deadline)) return denied("GLOBAL_DEADLINE")
        if (!now.isBefore(parent.deadline)) return denied("STAGE_DEADLINE")
        if (!validId(request.requestId) || !validId(request.taskId))
            return denied("INVALID_CHILD_IDENTITY")
        if (request.parentGraphId != parent.graphId ||
            request.parentStageRunId != parent.stageRunId || request.origin != parent.origin)
            return denied("PARENT_ORIGIN_MISMATCH")
        if (snapshot.nodes.any { it.parentGraphId == parent.graphId &&
                it.requestId == request.requestId })
            return denied("REPEATED_REQUEST")
        if (snapshot.nodes.any { it.parentGraphId == parent.graphId &&
                it.taskId == request.taskId })
            return denied("NO_PROGRESS_LOOP")
        var ancestor: RecursiveNode? = parent
        while (ancestor != null) {
            if (ancestor.taskId == request.taskId) return denied("NO_PROGRESS_LOOP")
            ancestor = ancestor.parentGraphId?.let(snapshot::node)
        }
        if (parent.depth >= budget.maxDepth) return denied("DEPTH_LIMIT")
        if (snapshot.nodes.size - 1 >= limits.maxTotalChildren) return denied("GLOBAL_CHILD_LIMIT")
        if (snapshot.nodes.count { it.parentGraphId == parent.graphId } >= budget.maxChildren)
            return denied("CHILD_COUNT_LIMIT")
        if (request.maxTokens <= 0 || request.maxToolCalls < 0)
            return denied("INVALID_CHILD_BUDGET")
        if (request.maxTokens > parent.granted.tokens - parent.spent.tokens -
                parent.delegated.tokens)
            return denied("TOKEN_BUDGET_EXHAUSTED")
        if (request.maxToolCalls > parent.granted.toolCalls - parent.spent.toolCalls -
                parent.delegated.toolCalls)
            return denied("TOOL_BUDGET_EXHAUSTED")
        if (request.maxDurationMillis <= 0 ||
            request.maxDurationMillis > limits.maxStageDurationMillis ||
            request.maxDurationMillis > budget.maxDurationMillis)
            return denied("STAGE_DURATION_LIMIT")
        val childDeadline = try { now.plusMillis(request.maxDurationMillis) }
            catch (_: Exception) { return denied("STAGE_DURATION_LIMIT") }
        if (childDeadline.isAfter(parent.deadline) || childDeadline.isAfter(limits.deadline))
            return denied("STAGE_DURATION_LIMIT")
        if (request.writablePaths.any { it !in parent.writablePaths ||
                it in parent.frozenPaths } ||
            !roleScope(parent.origin.role, request.writablePaths) ||
            request.allowedReadTools.any { it !in parent.allowedReadTools } ||
            !validReadTools(request.allowedReadTools))
            return denied("CHILD_AUTHORITY_ESCALATION")
        val childGraphId = childId(parent.graphId, parent.stageRunId, request.requestId)
        if (childGraphId in request.dependsOn || childGraphId == parent.graphId)
            return denied("CYCLIC_RECURSIVE_GRAPH")
        if (snapshot.node(childGraphId) != null) return denied("REPEATED_REQUEST")
        if (request.dependsOn.any {
                val sibling = snapshot.node(it)
                sibling == null || sibling.parentGraphId != parent.graphId
            })
            return denied("INVALID_CHILD_DEPENDENCY")
        return RecursiveDecision.Allowed(RecursiveNode(
            graphId = childGraphId, stageRunId = childStageId(childGraphId),
            parentGraphId = parent.graphId, parentStageRunId = parent.stageRunId,
            requestId = request.requestId, origin = parent.origin,
            taskId = request.taskId, dependencies = request.dependsOn,
            depth = parent.depth + 1, writablePaths = request.writablePaths,
            frozenPaths = parent.frozenPaths, allowedReadTools = request.allowedReadTools,
            granted = RecursiveUsage(request.maxTokens, request.maxToolCalls),
            deadline = childDeadline
        ))
    }

    fun authorizeCharge(node: RecursiveNode, usage: RecursiveUsage): RecursiveDecision {
        if (node.state != RecursiveNodeState.RUNNING) return denied("STAGE_NOT_RUNNING")
        if (!validUsage(usage)) return denied("INVALID_USAGE")
        if (usage.tokens > node.granted.tokens - node.spent.tokens - node.delegated.tokens)
            return denied("TOKEN_BUDGET_EXHAUSTED")
        if (usage.toolCalls > node.granted.toolCalls - node.spent.toolCalls -
                node.delegated.toolCalls)
            return denied("TOOL_BUDGET_EXHAUSTED")
        return RecursiveDecision.Allowed(node.copy(spent = RecursiveUsage(
            node.spent.tokens + usage.tokens, node.spent.toolCalls + usage.toolCalls
        )))
    }
}

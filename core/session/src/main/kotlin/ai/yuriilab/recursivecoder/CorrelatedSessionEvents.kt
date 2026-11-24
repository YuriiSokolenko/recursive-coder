package ai.yuriilab.recursivecoder

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Host-installed Session Protocol v1 journal adapters. The event types and actor
 * shape stay inside the original v1 schema; recursive metadata is scalar only.
 * Model prompts, response bodies, workspace bytes, tool output and credentials
 * never reach any of these sinks.
 *
 * These adapters propagate journal failures when invoked directly. The recursive
 * scheduler deliberately treats its event sink as best-effort telemetry, so
 * hosts must also inspect eventDeliveryFailed and verify journal.finish().
 */
class CorrelatedRecursiveSessionEvents(
    private val journal: SessionEventJournal
) : RecursiveEventSink {
    override fun emit(event: RecursiveEvent) {
        val kind = when (event.kind) {
            RecursiveEventKind.STARTED -> SessionEventKinds.AGENT_STARTED
            RecursiveEventKind.CHILD_RESERVED -> SessionEventKinds.SESSION_TRANSITION
            RecursiveEventKind.DENIED -> SessionEventKinds.POLICY_DENIED
            RecursiveEventKind.USAGE_CHARGED, RecursiveEventKind.INTEGRATED -> SessionEventKinds.TOOL_COMPLETED
            RecursiveEventKind.SUCCEEDED, RecursiveEventKind.CANCELLED,
            RecursiveEventKind.TIMED_OUT -> SessionEventKinds.AGENT_COMPLETED
            RecursiveEventKind.FAILED -> SessionEventKinds.SESSION_ERROR
        }
        val status = when (event.kind) {
            RecursiveEventKind.STARTED, RecursiveEventKind.CHILD_RESERVED -> SessionEventStatuses.STARTED
            RecursiveEventKind.DENIED -> SessionEventStatuses.DENIED
            RecursiveEventKind.USAGE_CHARGED, RecursiveEventKind.INTEGRATED,
            RecursiveEventKind.SUCCEEDED -> SessionEventStatuses.COMPLETED
            RecursiveEventKind.FAILED -> SessionEventStatuses.FAILED
            RecursiveEventKind.CANCELLED -> SessionEventStatuses.CANCELLED
            RecursiveEventKind.TIMED_OUT -> SessionEventStatuses.TIMED_OUT
        }
        val parentActorId = event.parentStageRunId?.let { "agent-" + it.value }
        val childActorId = when (event.kind) {
            RecursiveEventKind.CHILD_RESERVED -> "agent-" + event.stageRunId.value
            RecursiveEventKind.INTEGRATED -> event.childGraphId?.let {
                "agent-" + RecursiveGraphPolicy.childStageId(it).value
            }
            else -> null // Denied proposals do not identify an admitted agent.
        }
        journal.append(kind, status,
            correlationId = event.requestId ?: event.stageRunId.value,
            actor = SessionEventActor(
                agentId = if (event.kind == RecursiveEventKind.CHILD_RESERVED)
                    requireNotNull(parentActorId) { "CHILD_RESERVED requires an admitted parent actor" }
                    else "agent-" + event.stageRunId.value,
                parentAgentId = if (event.kind == RecursiveEventKind.CHILD_RESERVED)
                    null else parentActorId,
                childAgentId = childActorId
                // A child graph is not necessarily a distinct Session v1.
                // Unknown child session identity must remain null.
            ),
            extra = buildJsonObject {
                put("recursiveEvent", event.kind.name)
                put("graphId", event.graphId.value)
                put("stageRunId", event.stageRunId.value)
                put("origin", event.origin.name)
                put("role", event.origin.role.name)
                // Graph scope is not a host-issued budget correlation identifier.
                put("budgetScopeGraphId", event.graphId.value)
                put("grantTokens", event.granted.tokens)
                put("grantToolCalls", event.granted.toolCalls)
                put("spentTokens", event.usage.tokens)
                put("spentToolCalls", event.usage.toolCalls)
                put("delegatedTokens", event.delegated.tokens)
                put("delegatedToolCalls", event.delegated.toolCalls)
                put("parentGraphId", event.parentGraphId?.value?.let(::JsonPrimitive) ?: JsonNull)
                put("childGraphId", event.childGraphId?.value?.let(::JsonPrimitive) ?: JsonNull)
                put("requestId", event.requestId?.let(::JsonPrimitive) ?: JsonNull)
                event.code?.let { put("code", it) }
            })
    }
}

class CorrelatedRoleSessionEvents(
    private val journal: SessionEventJournal
) : StageRoleEventSink {
    override fun emit(event: StageRoleEvent) {
        val status = when (event.kind) {
            StageRoleEventKind.STARTED -> SessionEventStatuses.STARTED
            StageRoleEventKind.PROPOSED -> SessionEventStatuses.COMPLETED
            StageRoleEventKind.DENIED -> SessionEventStatuses.DENIED
            StageRoleEventKind.FAILED -> SessionEventStatuses.FAILED
            StageRoleEventKind.CANCELLED -> SessionEventStatuses.CANCELLED
            StageRoleEventKind.TIMED_OUT -> SessionEventStatuses.TIMED_OUT
        }
        journal.append(
            when (event.kind) {
                StageRoleEventKind.STARTED -> SessionEventKinds.AGENT_STARTED
                StageRoleEventKind.DENIED -> SessionEventKinds.POLICY_DENIED
                StageRoleEventKind.FAILED -> SessionEventKinds.SESSION_ERROR
                else -> SessionEventKinds.AGENT_COMPLETED
            }, status, correlationId = event.correlation.requestId,
            actor = event.agentId?.let { SessionEventActor(it.value) },
            extra = buildJsonObject {
                put("roleEvent", event.kind.name)
                put("graphId", event.correlation.graphId.value)
                put("stageRunId", event.correlation.stageRunId.value)
                put("role", event.role.name)
                put("requestId", event.correlation.requestId)
                put("modelRequests", event.usage.modelRequests)
                event.usage.inputTokens?.let { put("inputTokens", it) }
                event.usage.outputTokens?.let { put("outputTokens", it) }
                event.code?.let { put("code", it) }
            }
        )
    }
}

class CorrelatedPreparationSessionEvents(
    private val journal: SessionEventJournal
) : PreparationStageEventSink {
    override fun emit(event: PreparationStageEvent) {
        val type = when (event.state) {
            StageState.CREATED, StageState.READY -> SessionEventKinds.SESSION_TRANSITION
            StageState.RUNNING -> SessionEventKinds.AGENT_STARTED
            StageState.ACCEPTED -> SessionEventKinds.AGENT_COMPLETED
            StageState.REJECTED -> SessionEventKinds.POLICY_DENIED
            StageState.FAILED -> SessionEventKinds.SESSION_ERROR
            StageState.CANCELLED -> SessionEventKinds.AGENT_COMPLETED
        }
        val status = when (event.state) {
            StageState.CREATED, StageState.READY, StageState.RUNNING -> SessionEventStatuses.STARTED
            StageState.ACCEPTED -> SessionEventStatuses.COMPLETED
            StageState.REJECTED -> SessionEventStatuses.DENIED
            StageState.FAILED -> SessionEventStatuses.FAILED
            StageState.CANCELLED -> SessionEventStatuses.CANCELLED
        }
        journal.append(type, status,
            correlationId = event.correlation.requestId,
            extra = buildJsonObject {
                put("stageRunId", event.correlation.stageRunId.value)
                put("kind", event.kind.name)
                put("stageState", event.state.name)
                event.artifactId?.let { put("artifactId", it.value) }
                event.sha256?.let { put("artifactSha256", it) }
                event.approval?.let { put("approval", it.name) }
                event.code?.let { put("code", it) }
            })
    }
}

class CorrelatedReviewerSessionEvents(
    private val journal: SessionEventJournal
) : ReviewerDecisionSink {
    override fun emit(decision: ReviewerDecisionRecord) {
        journal.append(SessionEventKinds.AGENT_COMPLETED,
            if (decision.verdict == ReviewerDisposition.ACCEPT) SessionEventStatuses.COMPLETED else SessionEventStatuses.DENIED,
            correlationId = decision.correlation.requestId,
            extra = buildJsonObject {
                put("stageRunId", decision.correlation.stageRunId.value)
                put("role", EngineeringRole.REVIEWER.name)
                put("verdict", decision.verdict.name)
                put("reasonCode", decision.reasonCode)
                put("scope", decision.scope.name)
                put("reviewedSha256", decision.reviewedManifestSha256)
                put("evidenceCount", decision.evidenceIds.size)
            })
    }
}

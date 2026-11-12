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
 * These are synchronous, fail-closed durability boundaries when used directly:
 * a journal failure is propagated, never reported as a successful event write.
 */
class CorrelatedRecursiveSessionEvents(
    private val journal: SessionEventJournal
) : RecursiveEventSink {
    override fun emit(event: RecursiveEvent) {
        val kind = when (event.kind) {
            RecursiveEventKind.STARTED, RecursiveEventKind.CHILD_RESERVED -> "agent.started"
            RecursiveEventKind.DENIED -> "policy.denied"
            RecursiveEventKind.FAILED -> "session.error"
            else -> "agent.completed"
        }
        val status = when (event.kind) {
            RecursiveEventKind.STARTED, RecursiveEventKind.CHILD_RESERVED -> "started"
            RecursiveEventKind.DENIED -> "denied"
            RecursiveEventKind.FAILED -> "failed"
            RecursiveEventKind.CANCELLED -> "cancelled"
            RecursiveEventKind.TIMED_OUT -> "timed_out"
            else -> "completed"
        }
        journal.append(kind, status,
            correlationId = event.requestId ?: event.stageRunId.value,
            actor = SessionEventActor(
                agentId = "agent-" + event.stageRunId.value,
                parentAgentId = event.parentStageRunId?.let { "agent-" + it.value },
                childAgentId = event.childGraphId?.let { "agent-" + it.value },
                childSessionId = event.childGraphId?.value
            ),
            extra = buildJsonObject {
                put("recursiveEvent", event.kind.name)
                put("graphId", event.graphId.value)
                put("stageRunId", event.stageRunId.value)
                put("origin", event.origin.name)
                put("role", event.origin.role.name)
                put("budgetId", event.graphId.value)
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
            StageRoleEventKind.STARTED -> "started"
            StageRoleEventKind.PROPOSED -> "completed"
            StageRoleEventKind.DENIED -> "denied"
            StageRoleEventKind.FAILED -> "failed"
            StageRoleEventKind.CANCELLED -> "cancelled"
            StageRoleEventKind.TIMED_OUT -> "timed_out"
        }
        journal.append(
            when (event.kind) {
                StageRoleEventKind.STARTED -> "agent.started"
                StageRoleEventKind.DENIED -> "policy.denied"
                StageRoleEventKind.FAILED -> "session.error"
                else -> "agent.completed"
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
        val success = event.state == StageState.ACCEPTED
        journal.append(if (event.code != null) "policy.denied" else "agent.completed",
            if (event.code != null) "denied" else if (success) "completed" else "started",
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
        journal.append("agent.completed",
            if (decision.verdict == ReviewerDisposition.ACCEPT) "completed" else "denied",
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

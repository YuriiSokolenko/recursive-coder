package ai.yuriilab.recursivecoder

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@RequiresOptIn(
    message = "Trusted host-only running checkpoint; NOT effect or model execution authority.",
    level = RequiresOptIn.Level.ERROR
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class TrustedRunningBoundaryApi

/**
 * Deliberately one-way, metadata-only RUNNING checkpoint boundary (#104).
 *
 * A RUNNING record is NOT execution authority. Resume admission continues to
 * accept CREATED/ACCEPTED checkpoints exclusively; any running record (including
 * an uncovered tail) remains non-admissible until #105 authenticates effects.
 *
 * Do not widen PreExecutionPhasePolicy: its accepted prefix must stay stable.
 */
object TrustedRunningBoundaryPolicy {
    private val accepted = SessionPhaseVocabulary.acceptedWire
    private val running = SessionPhaseVocabulary.runningWire

    /**
     * Only the root, execution-free actor may publish this phase edge.
     * Physical ledger framing and identity/sequence checks remain mandatory.
     */
    fun step(event: JsonObject, currentPhase: String): String? {
        if (currentPhase != accepted ||
            (event["type"] as? JsonPrimitive)?.content != "session.transition" ||
            (event["status"] as? JsonPrimitive)?.content != "completed" ||
            event["timing"] != null) return null

        val actor = event["actor"] as? JsonObject ?: return null
        val id = actor["agentId"] as? JsonPrimitive ?: return null
        val sessionId = (event["sessionId"] as? JsonPrimitive)?.content ?: return null
        if (!id.isString || id.content != "agent-" + sessionId ||
            actor.keys != setOf("agentId", "parentAgentId", "childAgentId",
                "toolExecutionId", "childSessionId") ||
            actor.any { (key, value) -> key != "agentId" && value != JsonNull })
            return null

        val metadata = event["metadata"] as? JsonObject ?: return null
        if (metadata.keys != setOf("graphId")) return null
        val graphId = metadata["graphId"] as? JsonPrimitive ?: return null
        if (!graphId.isString || !ToolAuthorizationPolicy.validHostIdentifier(graphId.content))
            return null

        val edge = event["transition"] as? JsonObject ?: return null
        if (edge.keys != setOf("from", "to") ||
            (edge["from"] as? JsonPrimitive)?.content != accepted ||
            (edge["to"] as? JsonPrimitive)?.content != running)
            return null
        return running
    }

    /**
     * Precisely one last accepted->running edge following an independently
     * valid pre-execution prefix. No runtime, model, tool, receipt or child
     * events can appear on either side of the edge.
     */
    fun phaseFromVerifiedHistory(checkpointPhase: String, events: List<JsonObject>): String? {
        if (checkpointPhase != running || events.isEmpty()) return null
        if (PreExecutionPhasePolicy.phaseFromVerifiedHistory(accepted, events.dropLast(1)) != accepted)
            return null
        return step(events.last(), accepted)
    }
}

package ai.yuriilab.recursivecoder

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure, I/O-free history policy for the currently supported pre-execution
 * resume boundary. This is not model/tool replay authority. Both admission
 * and later fenced metadata publication must enforce this same phase proof.
 *
 * Compatibility change: an event type alone is not a legal lifecycle edge.
 */
object PreExecutionPhasePolicy {
    private val CREATED = SessionPhaseVocabulary.createdWire
    private val ACCEPTED = SessionPhaseVocabulary.acceptedWire

    private fun noExecutionActor(event: JsonObject): Boolean {
        val actor = event["actor"] as? JsonObject ?: return false
        if ((actor["agentId"] as? JsonPrimitive)?.content.isNullOrBlank()) return false
        // The durable event validator has a closed actor-key schema. Expand
        // this refusal list if that schema gains another execution actor key.
        return setOf("parentAgentId", "childAgentId", "childSessionId",
            "toolExecutionId").all { actor[it] == null || actor[it] == JsonNull }
    }

    /** Null means this event cannot safely extend the current phase. */
    fun step(event: JsonObject, currentPhase: String): String? {
        if (currentPhase !in setOf(CREATED, ACCEPTED) ||
            !noExecutionActor(event) ||
            (event["status"] as? JsonPrimitive)?.content != "completed")
            return null
        return when ((event["type"] as? JsonPrimitive)?.content) {
            "checkpoint.saved" ->
                if ("transition" !in event) currentPhase else null
            "session.transition" -> {
                val edge = event["transition"] as? JsonObject ?: return null
                val metadata = event["metadata"] as? JsonObject
                if (metadata != null && metadata.keys.any { it != "graphId" })
                    return null
                if (edge.keys != setOf("from", "to")) return null
                val from = (edge["from"] as? JsonPrimitive)?.content
                val to = (edge["to"] as? JsonPrimitive)?.content
                if (currentPhase == CREATED && from == CREATED && to == ACCEPTED)
                    to else null
            }
            else -> null
        }
    }

    /**
     * Created: no lifecycle edge. Accepted: exactly one explicit
     * created -> accepted edge. No implicit transition from a record type.
     */
    fun phaseFromVerifiedHistory(checkpointPhase: String, events: List<JsonObject>): String? {
        if (checkpointPhase !in setOf(CREATED, ACCEPTED)) return null
        var phase = CREATED
        for (event in events) phase = step(event, phase) ?: return null
        return if (phase == checkpointPhase) phase else null
    }
}

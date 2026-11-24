package ai.yuriilab.recursivecoder

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure, I/O-free history policy for the currently supported pre-execution
 * resume boundary. This is not a general runtime lifecycle validator or
 * model/tool replay authority: it accepts one pre-execution edge only. Both
 * admission and later fenced metadata publication enforce this same proof.
 *
 * Compatibility change: an event type alone is not a legal lifecycle edge.
 */
object PreExecutionPhasePolicy {
    private val CREATED = SessionPhaseVocabulary.createdWire
    private val ACCEPTED = SessionPhaseVocabulary.acceptedWire

    private fun noExecutionActor(event: JsonObject): Boolean {
        val actor = event[SessionEventFields.ACTOR] as? JsonObject ?: return false
        val agent = actor[SessionEventActorFields.AGENT_ID] as? JsonPrimitive ?: return false
        if (!agent.isString || agent.content.isBlank()) return false
        // Positive execution-free actor schema: new fields fail closed unless
        // absent/null. Only the root agent identity may be populated.
        return actor.all { (key, value) -> key == SessionEventActorFields.AGENT_ID || value == JsonNull }
    }

    /** Null means this event cannot safely extend the current phase. */
    fun step(event: JsonObject, currentPhase: String): String? {
        if (currentPhase !in setOf(CREATED, ACCEPTED) ||
            !noExecutionActor(event) ||
            (event[SessionEventFields.STATUS] as? JsonPrimitive)?.content != SessionEventStatuses.COMPLETED)
            return null
        // A phase-neutral event must also be execution-neutral. The same
        // metadata restriction applies to both allowed event types.
        val metadata = event[SessionEventFields.METADATA]
        if (metadata != null && (metadata !is JsonObject ||
            metadata.keys.any { it != StageEventMetadataFields.GRAPH_ID })) return null
        return when ((event[SessionEventFields.TYPE] as? JsonPrimitive)?.content) {
            SessionEventKinds.CHECKPOINT_SAVED ->
                if (SessionEventFields.TRANSITION !in event) currentPhase else null
            SessionEventKinds.SESSION_TRANSITION -> {
                val edge = event[SessionEventFields.TRANSITION] as? JsonObject ?: return null
                if (edge.keys != setOf(SessionEventTransitionFields.FROM, SessionEventTransitionFields.TO)) return null
                val from = (edge[SessionEventTransitionFields.FROM] as? JsonPrimitive)?.content
                val to = (edge[SessionEventTransitionFields.TO] as? JsonPrimitive)?.content
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

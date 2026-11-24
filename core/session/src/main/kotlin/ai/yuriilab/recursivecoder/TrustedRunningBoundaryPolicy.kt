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
            (event[SessionEventFields.TYPE] as? JsonPrimitive)?.content != SessionEventKinds.SESSION_TRANSITION ||
            (event[SessionEventFields.STATUS] as? JsonPrimitive)?.content != SessionEventStatuses.COMPLETED ||
            event[SessionEventFields.TIMING] != null) return null

        val actor = event[SessionEventFields.ACTOR] as? JsonObject ?: return null
        val id = actor[SessionEventActorFields.AGENT_ID] as? JsonPrimitive ?: return null
        val sessionId = (event[SessionEnvelopeFields.SESSION_ID] as? JsonPrimitive)?.content ?: return null
        if (!id.isString || id.content != "agent-" + sessionId ||
            actor.keys != setOf(SessionEventActorFields.AGENT_ID, SessionEventActorFields.PARENT_AGENT_ID, SessionEventActorFields.CHILD_AGENT_ID,
                SessionEventActorFields.TOOL_EXECUTION_ID, SessionEventActorFields.CHILD_SESSION_ID) ||
            actor.any { (key, value) -> key != SessionEventActorFields.AGENT_ID && value != JsonNull })
            return null

        val metadata = event[SessionEventFields.METADATA] as? JsonObject ?: return null
        if (metadata.keys != setOf(StageEventMetadataFields.GRAPH_ID)) return null
        val graphId = metadata[StageEventMetadataFields.GRAPH_ID] as? JsonPrimitive ?: return null
        if (!graphId.isString || !ToolAuthorizationPolicy.validHostIdentifier(graphId.content))
            return null

        val edge = event[SessionEventFields.TRANSITION] as? JsonObject ?: return null
        if (edge.keys != setOf(SessionEventTransitionFields.FROM, SessionEventTransitionFields.TO) ||
            (edge[SessionEventTransitionFields.FROM] as? JsonPrimitive)?.content != accepted ||
            (edge[SessionEventTransitionFields.TO] as? JsonPrimitive)?.content != running)
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

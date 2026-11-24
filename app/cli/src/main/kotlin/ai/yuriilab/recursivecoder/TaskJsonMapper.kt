package ai.yuriilab.recursivecoder

import kotlinx.serialization.json.*

/** CLI JSON projection stays outside the Task domain and uses the same v1 field order. */
internal fun Task.toPublicJson(): JsonObject = buildJsonObject {
    put("taskId", id); put(TaskWireFields.STATE, state.wire); put(TaskWireFields.PRIORITY, priority)
    put(TaskWireFields.DEPENDENCIES, buildJsonArray { dependencies.forEach { add(it) } })
    put(TaskWireFields.ATTEMPT, attempt)
    put(TaskWireFields.PRUNED_TRANSITIONS, prunedTransitions)
    put(TaskWireFields.SESSION_ID, sessionId?.let(::JsonPrimitive) ?: JsonNull)
    put(TaskWireFields.LAST_RESULT_STATUS, lastResultStatus?.wire?.let(::JsonPrimitive) ?: JsonNull)
    put(TaskWireFields.REASON, reason?.let(::JsonPrimitive) ?: JsonNull)
    put(TaskWireFields.TRANSITIONS, buildJsonArray {
        transitions.forEach { step ->
            add(buildJsonObject {
                put(TaskTransitionWireFields.FROM, step.from?.wire?.let(::JsonPrimitive) ?: JsonNull)
                put(TaskTransitionWireFields.TO, step.to.wire); put(TaskTransitionWireFields.AT, step.occurredAt)
                put(TaskWireFields.ATTEMPT, step.attempt)
                put(TaskWireFields.SESSION_ID, step.sessionId?.let(::JsonPrimitive) ?: JsonNull)
                put(TaskWireFields.REASON, step.reason?.let(::JsonPrimitive) ?: JsonNull)
            })
        }
    })
}

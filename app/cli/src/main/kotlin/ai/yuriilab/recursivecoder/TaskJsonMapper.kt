package ai.yuriilab.recursivecoder

import kotlinx.serialization.json.*

/** CLI JSON projection stays outside the Task domain and uses the same v1 field order. */
internal fun Task.toPublicJson(): JsonObject = buildJsonObject {
    put("taskId", id); put("state", state.wire); put("priority", priority)
    put("dependencies", buildJsonArray { dependencies.forEach { add(it) } })
    put("attempt", attempt)
    put("prunedTransitions", prunedTransitions)
    put("sessionId", sessionId?.let(::JsonPrimitive) ?: JsonNull)
    put("lastResultStatus", lastResultStatus?.wire?.let(::JsonPrimitive) ?: JsonNull)
    put("reason", reason?.let(::JsonPrimitive) ?: JsonNull)
    put("transitions", buildJsonArray {
        transitions.forEach { step ->
            add(buildJsonObject {
                put("from", step.from?.wire?.let(::JsonPrimitive) ?: JsonNull)
                put("to", step.to.wire); put("at", step.occurredAt)
                put("attempt", step.attempt)
                put("sessionId", step.sessionId?.let(::JsonPrimitive) ?: JsonNull)
                put("reason", step.reason?.let(::JsonPrimitive) ?: JsonNull)
            })
        }
    })
}

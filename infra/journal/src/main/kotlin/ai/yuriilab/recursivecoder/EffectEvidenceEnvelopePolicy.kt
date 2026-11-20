package ai.yuriilab.recursivecoder

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Closed-world RCLDG1 effect metadata policy shared by the physical writer
 * and read-only evidence projection. Merely containing a reserved "effect"
 * prefix is not authorization to write an effect: only an entire canonical
 * envelope on an exact event kind/status pair is admitted.
 *
 * This validates metadata shape, not independent RCEFX1 provenance.
 */
internal object EffectEvidenceEnvelopePolicy {
    private val base = setOf("effectOperationId", "effectFenceGeneration",
        "effectKind", "effectInputSha256", "effectRecordSequence",
        "effectRecordMacSha256", "effectState")
    private val sha = Regex("[0-9a-f]{64}")

    fun hasReservedKey(metadata: JsonObject): Boolean =
        metadata.keys.any { it.startsWith("effect") }

    private fun string(o: JsonObject, field: String): String? =
        (o[field] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun number(o: JsonObject, field: String): Long? =
        (o[field] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    fun validForEvent(type: String?, status: String?, metadata: JsonObject): Boolean {
        if (!hasReservedKey(metadata)) return false
        val state = string(metadata, "effectState") ?: return false
        val extra = when (state) {
            "intent", "started" -> emptySet()
            "committed" -> setOf("effectObservedStateSha256")
            "unconfirmed" -> setOf("effectErrorCode")
            else -> return false
        }
        if (metadata.keys != base + extra) return false
        val admissible = when (state) {
            "intent", "started" -> type == "tool.started" && status == "started"
            "committed" -> type == "tool.completed" && status == "completed"
            else -> type == "session.error" && status == "failed"
        }
        if (!admissible ||
            number(metadata, "effectFenceGeneration")?.let { it > 0L } != true ||
            number(metadata, "effectRecordSequence")?.let { it > 0L } != true ||
            string(metadata, "effectOperationId")?.let(
                ToolAuthorizationPolicy::validHostIdentifier) != true ||
            string(metadata, "effectInputSha256")?.let(sha::matches) != true ||
            string(metadata, "effectRecordMacSha256")?.let(sha::matches) != true ||
            EffectKind.entries.none { it.name == string(metadata, "effectKind") })
            return false
        return when (state) {
            "committed" -> string(metadata, "effectObservedStateSha256")
                ?.let(sha::matches) == true
            "unconfirmed" -> string(metadata, "effectErrorCode")
                ?.let(ToolAuthorizationPolicy::validHostIdentifier) == true
            else -> true
        }
    }
}

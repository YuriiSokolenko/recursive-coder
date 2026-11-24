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
    private val base = setOf(EffectEvidenceFields.EFFECT_OPERATION_ID, EffectEvidenceFields.EFFECT_FENCE_GENERATION,
        EffectEvidenceFields.EFFECT_KIND, EffectEvidenceFields.EFFECT_INPUT_SHA256, EffectEvidenceFields.EFFECT_RECORD_SEQUENCE,
        EffectEvidenceFields.EFFECT_RECORD_MAC_SHA256, EffectEvidenceFields.EFFECT_STATE)
    private val sha = Regex("[0-9a-f]{64}")

    fun hasReservedKey(metadata: JsonObject): Boolean =
        metadata.keys.any { it.startsWith("effect") }

    private fun string(o: JsonObject, field: String): String? =
        (o[field] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun number(o: JsonObject, field: String): Long? =
        (o[field] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    fun validForEvent(type: String?, status: String?, metadata: JsonObject): Boolean {
        if (!hasReservedKey(metadata)) return false
        val state = string(metadata, EffectEvidenceFields.EFFECT_STATE) ?: return false
        val extra = when (state) {
            EffectRecordStates.INTENT, EffectRecordStates.STARTED -> emptySet()
            EffectRecordStates.COMMITTED -> setOf(EffectEvidenceFields.EFFECT_OBSERVED_STATE_SHA256)
            EffectRecordStates.UNCONFIRMED -> setOf(EffectEvidenceFields.EFFECT_ERROR_CODE)
            else -> return false
        }
        if (metadata.keys != base + extra) return false
        val admissible = when (state) {
            EffectRecordStates.INTENT, EffectRecordStates.STARTED -> type == SessionEventKinds.TOOL_STARTED && status == SessionEventStatuses.STARTED
            EffectRecordStates.COMMITTED -> type == SessionEventKinds.TOOL_COMPLETED && status == SessionEventStatuses.COMPLETED
            else -> type == "session.error" && status == "failed"
        }
        if (!admissible ||
            number(metadata, EffectEvidenceFields.EFFECT_FENCE_GENERATION)?.let { it > 0L } != true ||
            number(metadata, EffectEvidenceFields.EFFECT_RECORD_SEQUENCE)?.let { it > 0L } != true ||
            string(metadata, EffectEvidenceFields.EFFECT_OPERATION_ID)?.let(
                ToolAuthorizationPolicy::validHostIdentifier) != true ||
            string(metadata, EffectEvidenceFields.EFFECT_INPUT_SHA256)?.let(sha::matches) != true ||
            string(metadata, EffectEvidenceFields.EFFECT_RECORD_MAC_SHA256)?.let(sha::matches) != true ||
            EffectKind.entries.none { it.name == string(metadata, EffectEvidenceFields.EFFECT_KIND) })
            return false
        return when (state) {
            EffectRecordStates.COMMITTED -> string(metadata, EffectEvidenceFields.EFFECT_OBSERVED_STATE_SHA256)
                ?.let(sha::matches) == true
            EffectRecordStates.UNCONFIRMED -> string(metadata, EffectEvidenceFields.EFFECT_ERROR_CODE)
                ?.let(ToolAuthorizationPolicy::validHostIdentifier) == true
            else -> true
        }
    }
}

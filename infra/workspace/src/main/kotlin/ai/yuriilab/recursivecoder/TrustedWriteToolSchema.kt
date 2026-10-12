package ai.yuriilab.recursivecoder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The model-facing operation schema: exact keys, exact types, finite batch size,
 * bounded UTF-8 payloads and mandatory optimistic concurrency digests.
 * The trusted host supplies roles, baseline, reviews and checks separately.
 */
object TrustedWriteToolSchema {
    fun decode(json: String): List<WriteOperation> {
        val root = try { Json.parseToJsonElement(json) as? JsonObject }
            catch (_: Exception) { null } ?: invalid()
        if (root.keys != setOf("operations")) invalid()
        val array = root["operations"] as? JsonArray ?: invalid()
        if (array.size !in 1..8) invalid()
        val operations = array.map { value ->
            val obj = value as? JsonObject ?: invalid()
            fun text(key: String): String = (obj[key] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content ?: invalid()
            val op = text("op")
            val path = text("path")
            if (path.isEmpty() || path.length > 4096) invalid()
            when (op) {
                "create" -> {
                    if (obj.keys != setOf("op", "path", "content")) invalid()
                    WriteOperation.Create(path, content(text("content")))
                }
                "edit" -> {
                    if (obj.keys != setOf("op", "path", "content", "expectedSha256")) invalid()
                    WriteOperation.Edit(path, hash(text("expectedSha256")), content(text("content")))
                }
                "delete" -> {
                    if (obj.keys != setOf("op", "path", "expectedSha256")) invalid()
                    WriteOperation.Delete(path, hash(text("expectedSha256")))
                }
                else -> invalid()
            }
        }
        if (operations.map { it.path }.toSet().size != operations.size) invalid()
        return operations
    }

    private fun content(s: String): String {
        if (s.toByteArray(Charsets.UTF_8).size > 64 * 1024 || '\u0000' in s) invalid()
        return s
    }

    private fun hash(s: String): String {
        if (!Regex("[a-f0-9]{64}").matches(s)) invalid()
        return s
    }

    private fun invalid(): Nothing =
        throw IllegalArgumentException("INVALID_WRITE_TOOL_SCHEMA")
}

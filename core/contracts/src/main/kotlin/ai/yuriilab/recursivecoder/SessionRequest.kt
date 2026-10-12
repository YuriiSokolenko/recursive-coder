package ai.yuriilab.recursivecoder

import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

class Refused(val code: String, val category: String, val explanation: String) : RuntimeException(explanation)
class Stopped(val timedOut: Boolean) : RuntimeException()

fun refuse(message: String): Nothing = throw Refused("INVALID_SESSION_REQUEST", "validation", message)
fun JsonObject.objectAt(key: String): JsonObject = this[key] as? JsonObject ?: refuse("Invalid object: $key")
fun JsonObject.textAt(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: refuse("Invalid string: $key")
fun JsonObject.longAt(key: String): Long =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: refuse("Invalid integer: $key")
fun JsonObject.stringsAt(key: String): List<String> =
    (this[key] as? JsonArray)?.map {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: refuse("Invalid array: $key")
    } ?: refuse("Invalid array: $key")
private fun JsonObject.fields(required: Set<String>, optional: Set<String> = emptySet()) {
    if (!keys.containsAll(required) || (keys - required - optional).isNotEmpty()) refuse("Unsupported or missing fields")
}
private fun JsonObject.optionalNull(key: String) {
    if (this[key] != null && this[key] != JsonNull) refuse("Unsupported non-null field: $key")
}

private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}")
private val sha256Pattern = Regex("[a-f0-9]{64}")
private val gitShaPattern = Regex("[a-f0-9]{40}")
private fun identifier(value: String) { if (!idPattern.matches(value)) refuse("Invalid identifier") }
private fun hash(value: String, pattern: Regex = sha256Pattern) {
    if (!pattern.matches(value)) refuse("Invalid digest")
}
private fun absolute(value: String): Path {
    val path = try { Path.of(value) } catch (_: Exception) { refuse("Invalid absolute path") }
    if (!path.isAbsolute || value.contains('\u0000') ||
        value.split('/').any { it == "." || it == ".." } || path.toString() != value
    ) refuse("Invalid absolute path")
    return path
}
class SessionRequest private constructor(val raw: JsonObject) {
    val requestId get() = raw.textAt("requestId")
    val sessionId get() = raw.textAt("sessionId")
    val traceId get() = raw.textAt("traceId")
    val task get() = raw.objectAt("task")
    val model get() = raw.objectAt("model")
    val policy get() = raw.objectAt("toolPolicy")
    val budgets get() = raw.objectAt("budgets")
    val outputs get() = raw.objectAt("outputs")
    val workspace get() = raw.objectAt("workspace")
    val invocation get() = raw.objectAt("invocation")
    val deadline get() = Instant.parse(budgets.textAt("deadlineAt"))
    val taskPath get() = absolute(task.objectAt("taskInputRef").textAt("path"))
    val rootPath get() = absolute(workspace.textAt("rootPath"))
    val outputRoot get() = absolute(outputs.textAt("rootDir"))
    fun output(key: String) = absolute(outputs.textAt(key))

    companion object {
        fun parse(content: String): SessionRequest {
            val obj = try { Json.parseToJsonElement(content) as? JsonObject }
                catch (_: Exception) { null } ?: refuse("Invalid JSON object")
            // Parse the entire v1 envelope before any filesystem or model action.
            obj.fields(setOf("kind", "version", "requestId", "sessionId", "traceId",
                "invocation", "workspace", "task", "model", "toolPolicy", "budgets",
                "outputs", "resume", "requiredCapabilities"), setOf("extensions"))
            if (obj.textAt("kind") != "session-request") refuse("Expected session-request")
            listOf("requestId", "sessionId", "traceId").forEach { identifier(obj.textAt(it)) }
            if (!Regex("1\\.[0-9]+").matches(obj.textAt("version"))) refuse("Invalid protocol version")
            val req = SessionRequest(obj)
            req.validateShape()
            return req
        }
    }

    private fun validateShape() {
        invocation.fields(setOf("source", "invocationId", "attempt"))
        identifier(invocation.textAt("invocationId"))
        if (invocation.longAt("attempt") < 1) refuse("Invalid attempt")

        workspace.fields(setOf("workspaceId", "rootPath", "baselineSha", "headSha"), setOf("branch"))
        identifier(workspace.textAt("workspaceId"))
        absolute(workspace.textAt("rootPath"))
        hash(workspace.textAt("baselineSha"), gitShaPattern)
        hash(workspace.textAt("headSha"), gitShaPattern)
        workspace["branch"]?.let { workspace.textAt("branch").also { b ->
            if (b.isBlank() || b.length > 128) refuse("Invalid branch")
        } }

        task.fields(setOf("stage", "role", "taskId", "taskInputRef"), setOf("parentAgentId"))
        listOf("stage", "role", "taskId").forEach { identifier(task.textAt(it)) }
        task["parentAgentId"]?.let { if (it != JsonNull) identifier(task.textAt("parentAgentId")) }
        val ref = task.objectAt("taskInputRef")
        ref.fields(setOf("path", "sha256"))
        absolute(ref.textAt("path"))
        hash(ref.textAt("sha256"))

        model.fields(setOf("provider", "modelId", "baseUrl", "credentialRef",
            "contextTokens", "maxOutputTokens", "features"), setOf("temperature", "connectTimeoutMs", "requestTimeoutMs"))
        identifier(model.textAt("provider"))
        if (model.textAt("modelId").isBlank() || model.textAt("modelId").length > 256) refuse("Invalid model")
        if (!Regex("https?://[^\\s?#]+/?").matches(model.textAt("baseUrl"))) refuse("Invalid model URL")
        if (!Regex("env:[A-Z][A-Z0-9_]*").matches(model.textAt("credentialRef"))) refuse("Invalid credential reference")
        if (model.longAt("contextTokens") < 1 || model.longAt("maxOutputTokens") < 1) refuse("Invalid model limits")
        model.stringsAt("features")
        listOf("connectTimeoutMs", "requestTimeoutMs").forEach { key ->
            model[key]?.let { if (model.longAt(key) !in 100..120000) refuse("Invalid model timeout") }
        }
        model["temperature"]?.let {
            val n = (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull
            if (n == null || !n.isFinite() || n < 0) refuse("Invalid temperature")
        }

        policy.fields(setOf("mode", "allowedTools", "deniedTools", "protectedPaths",
            "writablePaths", "policySha256"), setOf("immutableInputs"))
        if (policy.textAt("mode") !in setOf("read-only", "scoped-write")) refuse("Invalid tool mode")
        listOf("allowedTools", "deniedTools", "protectedPaths", "writablePaths").forEach { policy.stringsAt(it) }
        hash(policy.textAt("policySha256"))
        policy["immutableInputs"]?.let {
            val refs = it as? JsonArray ?: refuse("Invalid immutable inputs")
            refs.forEach { element ->
                val value = element as? JsonObject ?: refuse("Invalid immutable reference")
                value.fields(setOf("path", "sha256"))
                absolute(value.textAt("path"))
                hash(value.textAt("sha256"))
            }
        }

        budgets.fields(setOf("deadlineAt", "maxWallTimeMs", "maxTotalTokens", "maxToolCalls",
            "maxChildAgents", "maxDepth"), setOf("maxModelRequests"))
        try { Instant.parse(budgets.textAt("deadlineAt")) } catch (_: Exception) { refuse("Invalid deadline") }
        listOf("maxWallTimeMs", "maxTotalTokens").forEach {
            if (budgets.longAt(it) < 1) refuse("Invalid budget")
        }
        listOf("maxToolCalls", "maxChildAgents", "maxDepth").forEach {
            if (budgets.longAt(it) < 0) refuse("Invalid budget")
        }
        budgets["maxModelRequests"]?.let { if (budgets.longAt("maxModelRequests") < 1) refuse("Invalid budget") }

        outputs.fields(setOf("rootDir", "resultPath", "eventsPath", "checkpointDir", "diagnosticsPath"))
        listOf("rootDir", "resultPath", "eventsPath", "checkpointDir", "diagnosticsPath").forEach {
            absolute(outputs.textAt(it))
        }
        val targets = listOf("resultPath", "eventsPath", "diagnosticsPath", "checkpointDir").map { output(it) }
        if (targets.toSet().size != targets.size || targets.any { !it.startsWith(outputRoot) || it == outputRoot })
            refuse("Output files must be distinct and below the output root")

        val resume = raw.objectAt("resume")
        resume.fields(setOf("mode", "fenceGeneration", "checkpoint"))
        if (resume.textAt("mode") !in setOf("fresh", "resume") || resume.longAt("fenceGeneration") < 1)
            refuse("Invalid resume mode")
        if (resume["checkpoint"] != JsonNull) {
            val cp = resume.objectAt("checkpoint")
            cp.fields(setOf("path", "sha256"))
            absolute(cp.textAt("path"))
            hash(cp.textAt("sha256"))
        }
        raw.stringsAt("requiredCapabilities").forEach { identifier(it) }
        raw["extensions"]?.let {
            val extensions = it as? JsonObject ?: refuse("Invalid extensions")
            if (extensions.keys.any { key -> !key.startsWith("x-") }) refuse("Invalid extension key")
        }
    }

}

class Cancellation {
    private val requested = AtomicBoolean()
    private val expired = AtomicBoolean()
    @Volatile private var worker: Thread? = null
    fun bind() { worker = Thread.currentThread(); if (requested.get()) worker?.interrupt() }
    fun cancel() { requested.set(true); worker?.interrupt() }
    fun expire() { expired.set(true); worker?.interrupt() }
    fun check(deadline: Instant) {
        if (expired.get() || !Instant.now().isBefore(deadline)) throw Stopped(true)
        if (requested.get()) throw Stopped(false)
    }
}


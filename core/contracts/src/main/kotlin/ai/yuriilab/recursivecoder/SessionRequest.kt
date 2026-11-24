package ai.yuriilab.recursivecoder

import java.nio.file.Path
import java.time.Clock
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
    val requestId get() = raw.textAt(SessionEnvelopeFields.REQUEST_ID)
    val sessionId get() = raw.textAt(SessionEnvelopeFields.SESSION_ID)
    val traceId get() = raw.textAt(SessionEnvelopeFields.TRACE_ID)
    val task get() = raw.objectAt(SessionRequestFields.TASK)
    val model get() = raw.objectAt(SessionRequestFields.MODEL)
    val policy get() = raw.objectAt(SessionRequestFields.TOOL_POLICY)
    val budgets get() = raw.objectAt(SessionRequestFields.BUDGETS)
    val outputs get() = raw.objectAt(SessionRequestFields.OUTPUTS)
    val workspace get() = raw.objectAt(SessionRequestFields.WORKSPACE)
    val invocation get() = raw.objectAt(SessionRequestFields.INVOCATION)
    val deadline get() = Instant.parse(budgets.textAt(SessionBudgetFields.DEADLINE_AT))
    val taskPath get() = absolute(task.objectAt(TaskInputFields.TASK_INPUT_REF).textAt(SessionArtifactReferenceFields.PATH))
    val rootPath get() = absolute(workspace.textAt(WorkspaceFields.ROOT_PATH))
    val outputRoot get() = absolute(outputs.textAt(SessionOutputFields.ROOT_DIR))
    fun output(key: String) = absolute(outputs.textAt(key))

    companion object {
        fun parse(content: String): SessionRequest {
            val obj = try { Json.parseToJsonElement(content) as? JsonObject }
                catch (_: Exception) { null } ?: refuse("Invalid JSON object")
            // Parse the entire v1 envelope before any filesystem or model action.
            obj.fields(setOf(SessionEnvelopeFields.KIND, SessionEnvelopeFields.VERSION, SessionEnvelopeFields.REQUEST_ID, SessionEnvelopeFields.SESSION_ID, SessionEnvelopeFields.TRACE_ID,
                SessionRequestFields.INVOCATION, SessionRequestFields.WORKSPACE, SessionRequestFields.TASK, SessionRequestFields.MODEL, SessionRequestFields.TOOL_POLICY, SessionRequestFields.BUDGETS,
                SessionRequestFields.OUTPUTS, SessionRequestFields.RESUME, SessionRequestFields.REQUIRED_CAPABILITIES), setOf(SessionRequestFields.EXTENSIONS))
            if (obj.textAt(SessionEnvelopeFields.KIND) != SessionEnvelopeKinds.REQUEST) refuse("Expected session-request")
            listOf(SessionEnvelopeFields.REQUEST_ID, SessionEnvelopeFields.SESSION_ID, SessionEnvelopeFields.TRACE_ID).forEach { identifier(obj.textAt(it)) }
            if (!Regex("1\\.[0-9]+").matches(obj.textAt(SessionEnvelopeFields.VERSION))) refuse("Invalid protocol version")
            val req = SessionRequest(obj)
            req.validateShape()
            return req
        }
    }

    private fun validateShape() {
        invocation.fields(setOf(InvocationFields.SOURCE, InvocationFields.INVOCATION_ID, InvocationFields.ATTEMPT))
        identifier(invocation.textAt(InvocationFields.INVOCATION_ID))
        if (invocation.longAt(InvocationFields.ATTEMPT) < SessionProtocol.FIRST_INVOCATION_ATTEMPT) refuse("Invalid attempt")

        workspace.fields(setOf(WorkspaceFields.WORKSPACE_ID, WorkspaceFields.ROOT_PATH, WorkspaceFields.BASELINE_SHA, WorkspaceFields.HEAD_SHA), setOf(WorkspaceFields.BRANCH))
        identifier(workspace.textAt(WorkspaceFields.WORKSPACE_ID))
        absolute(workspace.textAt(WorkspaceFields.ROOT_PATH))
        hash(workspace.textAt(WorkspaceFields.BASELINE_SHA), gitShaPattern)
        hash(workspace.textAt(WorkspaceFields.HEAD_SHA), gitShaPattern)
        workspace[WorkspaceFields.BRANCH]?.let { workspace.textAt(WorkspaceFields.BRANCH).also { b ->
            if (b.isBlank() || b.length > 128) refuse("Invalid branch")
        } }

        task.fields(setOf(TaskInputFields.STAGE, TaskInputFields.ROLE, TaskInputFields.TASK_ID, TaskInputFields.TASK_INPUT_REF), setOf(TaskInputFields.PARENT_AGENT_ID))
        listOf(TaskInputFields.STAGE, TaskInputFields.ROLE, TaskInputFields.TASK_ID).forEach { identifier(task.textAt(it)) }
        task[TaskInputFields.PARENT_AGENT_ID]?.let { if (it != JsonNull) identifier(task.textAt(TaskInputFields.PARENT_AGENT_ID)) }
        val ref = task.objectAt(TaskInputFields.TASK_INPUT_REF)
        ref.fields(setOf(SessionArtifactReferenceFields.PATH, SessionArtifactReferenceFields.SHA256))
        absolute(ref.textAt(SessionArtifactReferenceFields.PATH))
        hash(ref.textAt(SessionArtifactReferenceFields.SHA256))

        model.fields(setOf(ModelConfigFields.PROVIDER, ModelConfigFields.MODEL_ID, ModelConfigFields.BASE_URL, ModelConfigFields.CREDENTIAL_REF,
            ModelConfigFields.CONTEXT_TOKENS, ModelConfigFields.MAX_OUTPUT_TOKENS, ModelConfigFields.FEATURES), setOf(ModelConfigFields.TEMPERATURE, ModelConfigFields.CONNECT_TIMEOUT_MS, ModelConfigFields.REQUEST_TIMEOUT_MS))
        identifier(model.textAt(ModelConfigFields.PROVIDER))
        if (model.textAt(ModelConfigFields.MODEL_ID).isBlank() || model.textAt(ModelConfigFields.MODEL_ID).length > 256) refuse("Invalid model")
        if (!Regex("https?://[^\\s?#]+/?").matches(model.textAt(ModelConfigFields.BASE_URL))) refuse("Invalid model URL")
        if (!Regex("env:[A-Z][A-Z0-9_]*").matches(model.textAt(ModelConfigFields.CREDENTIAL_REF))) refuse("Invalid credential reference")
        if (model.longAt(ModelConfigFields.CONTEXT_TOKENS) < 1 || model.longAt(ModelConfigFields.MAX_OUTPUT_TOKENS) < 1) refuse("Invalid model limits")
        model.stringsAt(ModelConfigFields.FEATURES)
        listOf(ModelConfigFields.CONNECT_TIMEOUT_MS, ModelConfigFields.REQUEST_TIMEOUT_MS).forEach { key ->
            model[key]?.let { if (model.longAt(key) !in 100..120000) refuse("Invalid model timeout") }
        }
        model[ModelConfigFields.TEMPERATURE]?.let {
            val n = (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull
            if (n == null || !n.isFinite() || n < 0) refuse("Invalid temperature")
        }

        policy.fields(setOf(ToolPolicyFields.MODE, ToolPolicyFields.ALLOWED_TOOLS, ToolPolicyFields.DENIED_TOOLS, ToolPolicyFields.PROTECTED_PATHS,
            ToolPolicyFields.WRITABLE_PATHS, ToolPolicyFields.POLICY_SHA256), setOf(ToolPolicyFields.IMMUTABLE_INPUTS))
        if (policy.textAt(ToolPolicyFields.MODE) !in setOf(ToolPolicyModes.READ_ONLY, ToolPolicyModes.SCOPED_WRITE)) refuse("Invalid tool mode")
        listOf(ToolPolicyFields.ALLOWED_TOOLS, ToolPolicyFields.DENIED_TOOLS, ToolPolicyFields.PROTECTED_PATHS, ToolPolicyFields.WRITABLE_PATHS).forEach { policy.stringsAt(it) }
        hash(policy.textAt(ToolPolicyFields.POLICY_SHA256))
        policy[ToolPolicyFields.IMMUTABLE_INPUTS]?.let {
            val refs = it as? JsonArray ?: refuse("Invalid immutable inputs")
            refs.forEach { element ->
                val value = element as? JsonObject ?: refuse("Invalid immutable reference")
                value.fields(setOf(SessionArtifactReferenceFields.PATH, SessionArtifactReferenceFields.SHA256))
                absolute(value.textAt(SessionArtifactReferenceFields.PATH))
                hash(value.textAt(SessionArtifactReferenceFields.SHA256))
            }
        }

        budgets.fields(setOf(SessionBudgetFields.DEADLINE_AT, SessionBudgetFields.MAX_WALL_TIME_MS, SessionBudgetFields.MAX_TOTAL_TOKENS, SessionBudgetFields.MAX_TOOL_CALLS,
            SessionBudgetFields.MAX_CHILD_AGENTS, SessionBudgetFields.MAX_DEPTH), setOf(SessionBudgetFields.MAX_MODEL_REQUESTS))
        try { Instant.parse(budgets.textAt(SessionBudgetFields.DEADLINE_AT)) } catch (_: Exception) { refuse("Invalid deadline") }
        listOf(SessionBudgetFields.MAX_WALL_TIME_MS, SessionBudgetFields.MAX_TOTAL_TOKENS).forEach {
            if (budgets.longAt(it) < 1) refuse("Invalid budget")
        }
        listOf(SessionBudgetFields.MAX_TOOL_CALLS, SessionBudgetFields.MAX_CHILD_AGENTS, SessionBudgetFields.MAX_DEPTH).forEach {
            if (budgets.longAt(it) < 0) refuse("Invalid budget")
        }
        budgets[SessionBudgetFields.MAX_MODEL_REQUESTS]?.let { if (budgets.longAt(SessionBudgetFields.MAX_MODEL_REQUESTS) < 1) refuse("Invalid budget") }

        outputs.fields(setOf(SessionOutputFields.ROOT_DIR, SessionOutputFields.RESULT_PATH, SessionOutputFields.EVENTS_PATH, SessionOutputFields.CHECKPOINT_DIR, SessionOutputFields.DIAGNOSTICS_PATH))
        listOf(SessionOutputFields.ROOT_DIR, SessionOutputFields.RESULT_PATH, SessionOutputFields.EVENTS_PATH, SessionOutputFields.CHECKPOINT_DIR, SessionOutputFields.DIAGNOSTICS_PATH).forEach {
            absolute(outputs.textAt(it))
        }
        val targets = listOf(SessionOutputFields.RESULT_PATH, SessionOutputFields.EVENTS_PATH, SessionOutputFields.DIAGNOSTICS_PATH, SessionOutputFields.CHECKPOINT_DIR).map { output(it) }
        if (targets.toSet().size != targets.size || targets.any { !it.startsWith(outputRoot) || it == outputRoot })
            refuse("Output files must be distinct and below the output root")

        val resume = raw.objectAt(SessionRequestFields.RESUME)
        resume.fields(setOf(SessionResumeFields.MODE, SessionResumeFields.FENCE_GENERATION, "checkpoint"))
        if (resume.textAt(SessionResumeFields.MODE) !in setOf(SessionResumeModes.FRESH, SessionResumeModes.RESUME) || resume.longAt(SessionResumeFields.FENCE_GENERATION) < 1)
            refuse("Invalid resume mode")
        if (resume["checkpoint"] != JsonNull) {
            val cp = resume.objectAt("checkpoint")
            cp.fields(setOf(SessionArtifactReferenceFields.PATH, SessionArtifactReferenceFields.SHA256))
            absolute(cp.textAt(SessionArtifactReferenceFields.PATH))
            hash(cp.textAt(SessionArtifactReferenceFields.SHA256))
        }
        raw.stringsAt(SessionRequestFields.REQUIRED_CAPABILITIES).forEach { identifier(it) }
        raw[SessionRequestFields.EXTENSIONS]?.let {
            val extensions = it as? JsonObject ?: refuse("Invalid extensions")
            if (extensions.keys.any { key -> !key.startsWith("x-") }) refuse("Invalid extension key")
        }
    }

}

/**
 * A sample of two independent atomic flags, not an atomic pair: cancel() and
 * expire() may race between reads. For any observed expiry or reached deadline,
 * timeout wins over cancellation. A concurrent update missed by one sample is
 * observed by a subsequent check (or interrupts the bound worker).
 */
data class CancellationSignals(val requested: Boolean, val expired: Boolean)

/** The optional clock keeps legacy check(deadline) callers testable. */
class Cancellation(private val clock: Clock = Clock.systemUTC()) {
    private val requested = AtomicBoolean()
    private val expired = AtomicBoolean()
    private var worker: Thread? = null

    /**
     * Serialize binding changes with cancellation so no request can interrupt
     * the worker after unbind returns. A prior cancellation or expiry also interrupts on bind.
     */
    @Synchronized fun bind() {
        worker = Thread.currentThread()
        if (requested.get() || expired.get()) worker?.interrupt()
    }

    /**
     * End interrupt delivery before clearing the flag and performing durable I/O.
     * Deliberately do not validate thread ownership here: unbind() is also called
     * from a scheduler finally block, where throwing would skip task completion.
     */
    @Synchronized fun unbind() {
        worker = null
    }

    /**
     * Interrupt only on the first request. A runner that consumes the interrupt
     * must cooperatively observe signals() or check(deadline) to stop; repeated
     * scheduler polls deliberately do not re-interrupt it during cleanup.
     */
    @Synchronized fun cancel() {
        if (requested.compareAndSet(false, true)) worker?.interrupt()
    }

    /** Expiry follows the same single-interrupt, cooperative-check policy as cancel(). */
    @Synchronized fun expire() {
        if (expired.compareAndSet(false, true)) worker?.interrupt()
    }

    fun signals(): CancellationSignals = CancellationSignals(requested.get(), expired.get())

    /** Check clock affinity without exposing or changing the bound time source. */
    fun usesClock(expected: Clock): Boolean = clock == expected

    /** Samples the injectable clock by default; explicit `now` is deterministic. */
    fun check(deadline: Instant, now: Instant = clock.instant()) {
        val sample = signals()
        if (sample.expired || !now.isBefore(deadline)) throw Stopped(true)
        if (sample.requested) throw Stopped(false)
    }
}


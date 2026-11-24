package ai.yuriilab.recursivecoder

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlinx.serialization.json.*

/** Validates incoming Session Protocol and projects a claimed Task into a new v1 attempt. */
internal object TaskRequestMapper {
    data class Submission(val taskId: String, val template: String)

    fun submission(content: String): Submission {
        require(content.toByteArray(StandardCharsets.UTF_8).size <= 262144) { "Request too large" }
        val request = SessionRequest.parse(content)
        return Submission(request.task.textAt(TaskInputFields.TASK_ID), request.raw.toString())
    }

    fun forAttempt(task: Task, runRoot: Path): SessionRequest {
        require(task.state == TaskState.RUNNING && task.sessionId != null)
        // A durable snapshot is not automatically a valid request or a trusted task identity.
        val source = SessionRequest.parse(task.requestTemplate)
        require(source.task.textAt(TaskInputFields.TASK_ID) == task.id) { "Task template ID mismatch" }
        val invocation = source.invocation
        val adjustedInvocation = JsonObject(invocation + mapOf(
            InvocationFields.SOURCE to JsonPrimitive(SessionProtocol.INVOCATION_SOURCE_SCHEDULER),
            InvocationFields.ATTEMPT to JsonPrimitive(task.attempt),
            InvocationFields.INVOCATION_ID to JsonPrimitive("invoke-" + task.id + "-" + task.attempt)
        ))
        val outputs = buildJsonObject {
            put(SessionOutputFields.ROOT_DIR, runRoot.toString())
            put(SessionOutputFields.RESULT_PATH, runRoot.resolve("result.json").toString())
            put(SessionOutputFields.EVENTS_PATH, runRoot.resolve("events.jsonl").toString())
            put(SessionOutputFields.CHECKPOINT_DIR, runRoot.resolve("checkpoints").toString())
            put(SessionOutputFields.DIAGNOSTICS_PATH, runRoot.resolve("diagnostics.json").toString())
        }
        return SessionRequest.parse(JsonObject(source.raw + mapOf(
            SessionEnvelopeFields.REQUEST_ID to JsonPrimitive("request-" + task.id + "-" + task.attempt),
            SessionEnvelopeFields.SESSION_ID to JsonPrimitive(task.sessionId),
            SessionRequestFields.INVOCATION to adjustedInvocation,
            SessionRequestFields.OUTPUTS to outputs
        )).toString())
    }
}

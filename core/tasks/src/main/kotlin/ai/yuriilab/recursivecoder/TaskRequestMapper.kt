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
        return Submission(request.task.textAt("taskId"), request.raw.toString())
    }

    fun forAttempt(task: Task, runRoot: Path): SessionRequest {
        require(task.state == TaskState.RUNNING && task.sessionId != null)
        // A durable snapshot is not automatically a valid request or a trusted task identity.
        val source = SessionRequest.parse(task.requestTemplate)
        require(source.task.textAt("taskId") == task.id) { "Task template ID mismatch" }
        val invocation = source.invocation
        val adjustedInvocation = JsonObject(invocation + mapOf(
            "source" to JsonPrimitive("scheduler"),
            "attempt" to JsonPrimitive(task.attempt),
            "invocationId" to JsonPrimitive("invoke-" + task.id + "-" + task.attempt)
        ))
        val outputs = buildJsonObject {
            put("rootDir", runRoot.toString())
            put("resultPath", runRoot.resolve("result.json").toString())
            put("eventsPath", runRoot.resolve("events.jsonl").toString())
            put("checkpointDir", runRoot.resolve("checkpoints").toString())
            put("diagnosticsPath", runRoot.resolve("diagnostics.json").toString())
        }
        return SessionRequest.parse(JsonObject(source.raw + mapOf(
            "requestId" to JsonPrimitive("request-" + task.id + "-" + task.attempt),
            "sessionId" to JsonPrimitive(task.sessionId),
            "invocation" to adjustedInvocation,
            "outputs" to outputs
        )).toString())
    }
}

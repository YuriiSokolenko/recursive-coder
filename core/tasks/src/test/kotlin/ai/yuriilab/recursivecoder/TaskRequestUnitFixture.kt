package ai.yuriilab.recursivecoder

import java.time.Instant
import kotlinx.serialization.json.*

// Valid shape, but no workspace, model, file store or external process is created.
internal fun taskRequestJsonForUnitTest(id: String) = buildJsonObject {
    put("kind", "session-request"); put("version", "1.0")
    put("requestId", "req-$id"); put("sessionId", "session-$id"); put("traceId", "trace-$id")
    put("invocation", buildJsonObject {
        put("source", "cli"); put("invocationId", "inv-$id"); put("attempt", 1)
    })
    put("workspace", buildJsonObject {
        put("workspaceId", "ws-$id"); put("rootPath", "/virtual/workspace")
        put("baselineSha", "a".repeat(40)); put("headSha", "a".repeat(40))
        put("branch", "dev")
    })
    put("task", buildJsonObject {
        put("stage", "sample"); put("role", "reader"); put("taskId", id)
        put("taskInputRef", buildJsonObject {
            put("path", "/virtual/workspace/task.md"); put("sha256", "b".repeat(64))
        })
    })
    put("model", buildJsonObject {
        put("provider", "fake"); put("modelId", "fake-v1")
        put("baseUrl", "http://fake.invalid/v1"); put("credentialRef", "env:FAKE_TOKEN")
        put("contextTokens", 8192); put("maxOutputTokens", 128)
        put("features", buildJsonArray { add("tool_calls") })
    })
    put("toolPolicy", buildJsonObject {
        put("mode", "read-only")
        put("allowedTools", buildJsonArray { add("read") })
        put("deniedTools", buildJsonArray { add("safe_edit") })
        put("protectedPaths", buildJsonArray { add(".git/**") })
        put("writablePaths", buildJsonArray {})
        put("policySha256", "c".repeat(64))
    })
    put("budgets", buildJsonObject {
        put("deadlineAt", Instant.parse("2030-01-01T00:00:00Z").toString())
        put("maxWallTimeMs", 12000); put("maxTotalTokens", 10000)
        put("maxToolCalls", 1); put("maxChildAgents", 0); put("maxDepth", 0)
        put("maxModelRequests", 2)
    })
    put("outputs", buildJsonObject {
        put("rootDir", "/virtual/outputs/$id")
        put("resultPath", "/virtual/outputs/$id/result.json")
        put("eventsPath", "/virtual/outputs/$id/events.jsonl")
        put("checkpointDir", "/virtual/outputs/$id/checkpoints")
        put("diagnosticsPath", "/virtual/outputs/$id/diagnostics.json")
    })
    put("resume", buildJsonObject {
        put("mode", "fresh"); put("fenceGeneration", 1); put("checkpoint", JsonNull)
    })
    put("requiredCapabilities", buildJsonArray {
        add("session-events-v1"); add("fake-model-v1"); add("read-only-tools-v1")
    })
}.toString()

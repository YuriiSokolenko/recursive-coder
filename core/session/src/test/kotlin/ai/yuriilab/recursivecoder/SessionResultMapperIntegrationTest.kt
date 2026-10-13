package ai.yuriilab.recursivecoder

import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/** The Path-typed result artifact makes these mapper checks part of the integration test tier. */
@Tag("integration")
class SessionResultMapperIntegrationTest {
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val root = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
    private val output = root.resolve("mapper-fixture")

    private fun request(): SessionRequest = SessionRequest.parse(buildJsonObject {
        put("kind", "session-request"); put("version", "1.0")
        put("requestId", "request-fixture"); put("sessionId", "session-fixture")
        put("traceId", "trace-fixture")
        put("invocation", buildJsonObject {
            put("source", "cli"); put("invocationId", "invoke-fixture"); put("attempt", 1)
        })
        put("workspace", buildJsonObject {
            put("workspaceId", "workspace-fixture"); put("rootPath", root.toString())
            put("baselineSha", "a".repeat(40)); put("headSha", "a".repeat(40))
        })
        put("task", buildJsonObject {
            put("stage", "sample"); put("role", "reader"); put("taskId", "task-fixture")
            put("taskInputRef", buildJsonObject {
                put("path", root.resolve("task.md").toString()); put("sha256", "b".repeat(64))
            })
        })
        put("model", buildJsonObject {
            put("provider", "fake"); put("modelId", "fake-v1")
            put("baseUrl", "http://fake.invalid/v1"); put("credentialRef", "env:FAKE_TOKEN")
            put("contextTokens", 8192); put("maxOutputTokens", 128)
            put("features", buildJsonArray { add("tool_calls") })
        })
        put("toolPolicy", buildJsonObject {
            put("mode", "read-only"); put("allowedTools", buildJsonArray { add("read") })
            put("deniedTools", buildJsonArray { add("safe_edit") })
            put("protectedPaths", buildJsonArray { add(".git/**") })
            put("writablePaths", buildJsonArray {})
            put("policySha256", "c".repeat(64))
        })
        put("budgets", buildJsonObject {
            put("deadlineAt", start.plusSeconds(120).toString())
            put("maxWallTimeMs", 30000); put("maxTotalTokens", 10000)
            put("maxToolCalls", 1); put("maxChildAgents", 0); put("maxDepth", 0)
            put("maxModelRequests", 2)
        })
        put("outputs", buildJsonObject {
            put("rootDir", output.toString())
            put("resultPath", output.resolve("result.json").toString())
            put("eventsPath", output.resolve("events.jsonl").toString())
            put("checkpointDir", output.resolve("checkpoints").toString())
            put("diagnosticsPath", output.resolve("diagnostics.json").toString())
        })
        put("resume", buildJsonObject {
            put("mode", "fresh"); put("fenceGeneration", 1); put("checkpoint", JsonNull)
        })
        put("requiredCapabilities", buildJsonArray {
            add("session-events-v1"); add("fake-model-v1"); add("read-only-tools-v1")
        })
    }.toString())

    private fun completed(request: SessionRequest) = SessionRunResult(
        outcome = SessionOutcome.SUCCEEDED, startedAt = start, finishedAt = start.plusSeconds(1),
        lastEventSeq = 5,
        eventLog = SessionArtifact(request.output("eventsPath"), "a".repeat(64)),
        diagnostics = SessionArtifact(request.output("diagnosticsPath"), "b".repeat(64)),
        failure = null, usage = SessionBudgetState(inputTokens = 3, outputTokens = 4)
    )

    @Test fun successfulEncodePreservesV1KeysOrderAndArtifactProvenance() {
        val request = request()
        val json = SessionResultMapper.encode(completed(request), request)
        assertEquals(listOf("kind", "version", "sessionId", "traceId", "requestId",
            "status", "startedAt", "finishedAt", "durationMs", "lastEventSeq",
            "provenance", "outputs", "error", "usage", "invocation"), json.keys.toList())
        assertEquals("session-result", json.textAt("kind"))
        assertEquals(SessionProtocol.VERSION, json.textAt("version"))
        assertEquals("succeeded", json.textAt("status"))
        assertEquals(1000L, json.longAt("durationMs"))
        assertEquals(5L, json.longAt("lastEventSeq"))
        assertEquals(7L, json.objectAt("usage").longAt("totalTokens"))
        assertEquals(1L, json.objectAt("provenance").longAt("fenceGeneration"))
        val outputs = json.objectAt("outputs")
        assertEquals(listOf("eventLog", "diagnostics", "terminalReceipt",
            "changeManifest", "checkpoint"), outputs.keys.toList())
        assertEquals("a".repeat(64), outputs.objectAt("eventLog").textAt("sha256"))
        assertEquals("b".repeat(64), outputs.objectAt("diagnostics").textAt("sha256"))
        assertEquals(JsonNull, json.getValue("error"))
        assertEquals(request.invocation, json.objectAt("invocation"))
    }

    @Test fun rejectedResultsAreSerializedWithSafeErrorDetails() {
        val request = request()
        val failed = completed(request).copy(outcome = SessionOutcome.REJECTED,
            failure = SessionFailure("DENIED", "policy", "Safe rejection"))
        val json = SessionResultMapper.encode(failed, request)
        assertEquals("rejected", json.textAt("status"))
        val error = json.objectAt("error")
        assertEquals("DENIED", error.textAt("code"))
        assertEquals("policy", error.textAt("category"))
        assertEquals("Safe rejection", error.textAt("safeMessage"))
    }

    @Test fun encodeRejectsInvalidTerminalInvariantsWithFixedMessages() {
        val request = request()
        val good = completed(request)
        val invalid = listOf(
            good.copy(lastEventSeq = -1) to "Invalid session event sequence",
            good.copy(outcome = SessionOutcome.FAILED) to "Inconsistent session terminal result",
            good.copy(failure = SessionFailure("BAD", "policy", "secret")) to
                "Inconsistent session terminal result",
            good.copy(eventLog = good.eventLog.copy(sha256 = "BAD")) to
                "Invalid session artifact digest",
            good.copy(diagnostics = good.diagnostics.copy(sha256 = "BAD")) to
                "Invalid session artifact digest"
        )
        invalid.forEach { (result, message) ->
            val failure = assertFailsWith<IllegalArgumentException> {
                SessionResultMapper.encode(result, request)
            }
            assertEquals(message, failure.message)
        }
    }
}

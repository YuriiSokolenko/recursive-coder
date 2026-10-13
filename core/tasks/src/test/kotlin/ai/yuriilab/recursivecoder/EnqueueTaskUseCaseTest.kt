package ai.yuriilab.recursivecoder

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

@Tag("unit")
class EnqueueTaskUseCaseTest {
    private val fixed = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

    // Valid shape, but no workspace, model, file store or external process is created.
    private fun request(id: String) = buildJsonObject {
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

    @Test fun interfacePerformsAtomicPolicyDrivenSubmission() {
        val repository = InMemoryTaskRepository()
        val useCase: EnqueueTaskUseCase = DefaultEnqueueTaskUseCase(repository, fixed)
        val first = useCase(request("first"))
        val second = useCase(request("second"), 80, listOf(first.id))

        assertEquals(TaskState.READY, first.state)
        assertEquals(TaskState.BLOCKED, second.state)
        assertEquals(80, second.priority)
        assertEquals("2026-01-01T00:00:00Z", first.transitions.first().occurredAt)
        assertEquals(listOf("first", "second"), repository.read { it.tasks.map(Task::id) })
    }

    @Test fun rejectionNeverPublishesPartialQueue() {
        val repository = InMemoryTaskRepository()
        val useCase: EnqueueTaskUseCase = DefaultEnqueueTaskUseCase(repository, fixed)
        assertFailsWith<IllegalArgumentException> { useCase(request("x"), -1) }
        assertFailsWith<Refused> { useCase("{}") }
        assertTrue(repository.read { it.tasks }.isEmpty())

        useCase(request("ok"))
        val before = repository.read { it }
        assertFailsWith<IllegalArgumentException> { useCase(request("missing"), 50, listOf("absent")) }
        assertEquals(before, repository.read { it })
    }

    @Test fun competingSubmissionsOfSameIdYieldOneCommit() {
        val repository = InMemoryTaskRepository()
        val useCase: EnqueueTaskUseCase = DefaultEnqueueTaskUseCase(repository, fixed)
        val submission = request("same")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val attempts = (0 until 6).map {
                pool.submit<Boolean> {
                    start.await()
                    try {
                        useCase(submission)
                        true
                    } catch (_: IllegalArgumentException) {
                        false
                    }
                }
            }
            start.countDown()
            assertEquals(1, attempts.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(1, repository.read { it.tasks.size })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun facadeDelegatesToSubstitutedInterface() {
        val repository = InMemoryTaskRepository()
        val expected = Task("substituted", "{}", emptyList(), 50)
        val substitute = object : EnqueueTaskUseCase {
            override fun invoke(requestJson: String, priority: Int, dependencies: List<String>): Task = expected
        }
        val service = TaskService(repository, fixed, substitute)
        assertSame(expected, service.enqueue("intentionally not valid JSON"))
        assertTrue(repository.read { it.tasks }.isEmpty())
    }
}

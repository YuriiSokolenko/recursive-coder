package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir

class TaskManagerTest {
    @TempDir lateinit var temp: Path

    private fun service() = TaskService(TaskStore(temp.resolve("store")))
    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(temp.resolve("workspace").toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return output.trim()
    }
    private fun workspace(): Triple<Path, String, String> {
        val root = temp.resolve("workspace")
        if (!Files.exists(root.resolve(".git"))) {
            Files.createDirectories(root)
            git("init", "-b", "dev")
            git("config", "user.email", "test@example.invalid")
            git("config", "user.name", "Test")
            Files.writeString(root.resolve("task.md"), "Observe the committed task")
            git("add", "task.md")
            git("commit", "-m", "task fixture")
        }
        val bytes = Files.readAllBytes(root.resolve("task.md"))
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return Triple(root, git("rev-parse", "HEAD"), hash)
    }

    private fun request(id: String): String {
        val (root, head, digest) = workspace()
        val out = temp.resolve("unused-" + id)
        return buildJsonObject {
            put("kind", "session-request"); put("version", "1.0")
            put("requestId", "request-" + id)
            put("sessionId", "original-" + id)
            put("traceId", "trace-" + id)
            put("invocation", buildJsonObject {
                put("source", "cli"); put("invocationId", "original-" + id)
                put("attempt", 1)
            })
            put("workspace", buildJsonObject {
                put("workspaceId", "workspace-" + id)
                put("rootPath", root.toString()); put("baselineSha", head)
                put("headSha", head); put("branch", "dev")
            })
            put("task", buildJsonObject {
                put("stage", "sample"); put("role", "reader"); put("taskId", id)
                put("taskInputRef", buildJsonObject {
                    put("path", root.resolve("task.md").toString()); put("sha256", digest)
                })
                put("parentAgentId", JsonNull)
            })
            put("model", buildJsonObject {
                put("provider", "fake"); put("modelId", "fake-v1")
                put("baseUrl", "http://fake.invalid/v1")
                put("credentialRef", "env:FAKE_TOKEN")
                put("contextTokens", 8192); put("maxOutputTokens", 128)
                put("features", buildJsonArray { add("tool_calls") })
            })
            put("toolPolicy", buildJsonObject {
                put("mode", "read-only")
                put("allowedTools", buildJsonArray { add("read") })
                put("deniedTools", buildJsonArray { add("safe_edit") })
                put("protectedPaths", buildJsonArray { add(".git/**") })
                put("writablePaths", buildJsonArray { })
                put("policySha256", "a".repeat(64))
            })
            put("budgets", buildJsonObject {
                put("deadlineAt", Instant.now().plusSeconds(120).toString())
                put("maxWallTimeMs", 30000); put("maxTotalTokens", 10000)
                put("maxToolCalls", 1); put("maxChildAgents", 0); put("maxDepth", 0)
                put("maxModelRequests", 2)
            })
            put("outputs", buildJsonObject {
                put("rootDir", out.toString())
                put("resultPath", out.resolve("result.json").toString())
                put("eventsPath", out.resolve("events.jsonl").toString())
                put("checkpointDir", out.resolve("checkpoints").toString())
                put("diagnosticsPath", out.resolve("diagnostics.json").toString())
            })
            put("resume", buildJsonObject {
                put("mode", "fresh"); put("fenceGeneration", 1); put("checkpoint", JsonNull)
            })
            put("requiredCapabilities", buildJsonArray {
                add("session-events-v1"); add("fake-model-v1"); add("read-only-tools-v1")
            })
        }.toString()
    }

    @Test fun priorityAndDAGOrderingWithSuccessPropagation() {
        val service = service()
        service.enqueue(request("root"), priority = 0)
        service.enqueue(request("child"), priority = 100, dependencies = listOf("root"))
        service.enqueue(request("other"), priority = 50)
        assertEquals(TaskState.BLOCKED, service.show("child").state)
        val order = mutableListOf<String>()
        val report = TaskScheduler(service, TaskRunner { request, _ ->
            order.add(request.task.getValue("taskId").jsonPrimitive.content)
            "succeeded"
        }).runUntilIdle()
        assertEquals(listOf("other", "root", "child"), order)
        assertEquals(3, report.completed)
        assertTrue(service.list().all { it.state == TaskState.SUCCEEDED })
        assertEquals(listOf(0, 1, 1), listOf(0, service.show("root").attempt, service.show("child").attempt))
    }

    @Test fun dependencyCyclesAndUnknownEdgesAreRejectedAtomically() {
        val service = service()
        service.enqueue(request("first"))
        service.enqueue(request("second"), dependencies = listOf("first"))
        assertFailsWith<IllegalStateException> {
            service.replaceDependencies("first", listOf("second"))
        }
        assertEquals(emptyList(), service.show("first").dependencies)
        assertFailsWith<IllegalArgumentException> {
            service.enqueue(request("third"), dependencies = listOf("missing"))
        }
        assertFailsWith<IllegalArgumentException> {
            service.replaceDependencies("first", listOf("first"))
        }
        assertEquals(2, service.list().size)
    }

    @Test fun cancelledDependencyRequiresAttentionThenRequeuesAfterRetry() {
        val service = service()
        service.enqueue(request("parent"))
        service.enqueue(request("child"), dependencies = listOf("parent"))
        service.cancel("parent")
        assertEquals(TaskState.CANCELLED, service.show("parent").state)
        assertEquals(TaskState.NEEDS_ATTENTION, service.show("child").state)
        assertEquals("dependency:parent:cancelled", service.show("child").reason)
        service.retry("parent")
        assertEquals(TaskState.BLOCKED, service.show("child").state)
        TaskScheduler(service, TaskRunner { _, _ -> "succeeded" }).runUntilIdle()
        assertEquals(TaskState.SUCCEEDED, service.show("child").state)
    }

    @Test fun failedParentDoesNotRunDependentAndCanBeRetried() {
        val service = service()
        service.enqueue(request("root"))
        service.enqueue(request("child"), dependencies = listOf("root"))
        val first = TaskScheduler(service, TaskRunner { _, _ -> "failed" }).runUntilIdle()
        assertEquals(1, first.launched)
        assertEquals(TaskState.FAILED, service.show("root").state)
        assertEquals(TaskState.NEEDS_ATTENTION, service.show("child").state)
        service.retry("root")
        TaskScheduler(service, TaskRunner { _, _ -> "succeeded" }).runUntilIdle()
        assertEquals(2, service.show("root").attempt)
        assertEquals(1, service.show("child").attempt)
    }

    @Test fun starvationGuardAllowsLowPriorityWithinBoundedClaims() {
        val service = service()
        service.enqueue(request("low"), priority = 0)
        repeat(25) {
            service.enqueue(request("high" + it), priority = 100)
        }
        val claimed = mutableListOf<String>()
        repeat(26) {
            val task = service.claim()!!
            claimed.add(task.id)
            service.finish(task.id, task.sessionId!!, "succeeded")
        }
        assertTrue(claimed.indexOf("low") in 1..12, claimed.toString())
    }

    @Test fun twoStoreInstancesCannotClaimSameTask() {
        val a = service()
        a.enqueue(request("only"))
        val b = service()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 8).map { index ->
                pool.submit<Task?> {
                    start.await()
                    (if (index % 2 == 0) a else b).claim()
                }
            }
            start.countDown()
            val claims = futures.map { it.get(5, TimeUnit.SECONDS) }.filterNotNull()
            assertEquals(1, claims.size)
            assertEquals("only", claims.single().id)
            assertEquals(1, a.show("only").attempt)
        } finally { pool.shutdownNow() }
    }

    @Test fun concurrencyBoundHoldsWhenMoreTasksAreReady() {
        val service = service()
        repeat(4) { service.enqueue(request("item" + it)) }
        val gate = CountDownLatch(1)
        val started = CountDownLatch(2)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val worker = TaskRunner { _, _ ->
            val current = running.incrementAndGet()
            peak.accumulateAndGet(current, ::maxOf)
            started.countDown()
            try {
                assertTrue(gate.await(5, TimeUnit.SECONDS))
                "succeeded"
            } finally { running.decrementAndGet() }
        }
        val scheduler = thread {
            TaskScheduler(service, worker).runUntilIdle(concurrency = 2)
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals(2, service.list().count { it.state == TaskState.RUNNING })
            assertEquals(2, peak.get())
        } finally { gate.countDown(); scheduler.join(10000) }
        assertFalse(scheduler.isAlive)
        assertEquals(4, service.list().count { it.state == TaskState.SUCCEEDED })
        assertTrue(peak.get() <= 2)
    }

    @Test fun pauseResumeAndGlobalCancellationArePersisted() {
        val service = service()
        service.enqueue(request("one"))
        service.pause()
        assertEquals(TaskState.PAUSED, service.show("one").state)
        assertEquals(0, TaskScheduler(service).runUntilIdle().launched)
        assertTrue(service().isPaused())
        service.resume()
        assertEquals(TaskState.READY, service.show("one").state)
        service.cancelAll()
        assertEquals(TaskState.CANCELLED, service.show("one").state)
        assertTrue(service().isPaused())
        service.resume()
        assertEquals(0, TaskScheduler(service).runUntilIdle().launched)
    }

    @Test fun completedResultCannotOverrideConcurrentCancellation() {
        val service = service()
        service.enqueue(request("one"))
        val claimed = service.claim()!!
        service.cancel("one")
        service.finish("one", claimed.sessionId!!, "succeeded")
        assertEquals(TaskState.CANCELLED, service.show("one").state)
    }

    @Test fun interruptedSchedulerFailsClosedAndManualRetryUsesNewSessionId() {
        val service = service()
        service.enqueue(request("one"))
        val claimed = service.claim()!!
        // A new scheduler process is the sole recovery authority.
        assertEquals(0, TaskScheduler(service(), TaskRunner { _, _ ->
            error("Interrupted attempt must never be replayed")
        }).runUntilIdle().launched)
        assertEquals(TaskState.NEEDS_ATTENTION, service.show("one").state)
        assertEquals(claimed.sessionId, service.show("one").sessionId)
        service.retry("one")
        TaskScheduler(service, TaskRunner { request, _ ->
            assertEquals(2, request.invocation.getValue("attempt").jsonPrimitive.int)
            assertEquals("scheduler", request.invocation.getValue("source").jsonPrimitive.content)
            "succeeded"
        }).runUntilIdle()
        assertEquals("session-one-2", service.show("one").sessionId)
        assertEquals(2, service.show("one").attempt)
        assertTrue(service.show("one").transitions.any {
            it.to == TaskState.NEEDS_ATTENTION && it.reason!!.startsWith("interrupted:")
        })
    }

    @Test fun corruptSnapshotIsNotSilentlyDiscarded() {
        val service = service()
        service.enqueue(request("one"))
        val path = temp.resolve("store/tasks.json")
        Files.writeString(path, "{broken")
        assertFailsWith<IllegalStateException> { service().list() }
        assertEquals("{broken", Files.readString(path))
    }

    @Test fun actualFakeSessionIsDispatchedWithoutExternalRunner() {
        val service = service()
        service.enqueue(request("readtask"))
        val report = TaskScheduler(service).runUntilIdle()
        assertEquals(1, report.launched)
        assertEquals(TaskState.SUCCEEDED, service.show("readtask").state)
        assertEquals("succeeded", service.show("readtask").lastResultStatus)
        val result = temp.resolve("store/runs/readtask/attempt-1/result.json")
        assertEquals("session-readtask-1",
            Json.parseToJsonElement(Files.readString(result)).jsonObject["sessionId"]!!.jsonPrimitive.content)
        assertTrue(Files.isRegularFile(temp.resolve("store/runs/readtask/attempt-1/events.jsonl")))
    }
}

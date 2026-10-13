package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag

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

// Scheduler unit tests use an injected, deterministic runner; cross-module execution is tested in :app:cli.
private val schedulerTestRunner = TaskRunner { _, _ -> "succeeded" }

@Tag("integration")
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
        assertFailsWith<IllegalStateException> {
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
        assertEquals(0, TaskScheduler(service, schedulerTestRunner).runUntilIdle().launched)
        assertTrue(service().isPaused())
        service.resume()
        assertEquals(TaskState.READY, service.show("one").state)
        service.cancelAll()
        assertEquals(TaskState.CANCELLED, service.show("one").state)
        assertTrue(service().isPaused())
        service.resume()
        assertEquals(0, TaskScheduler(service, schedulerTestRunner).runUntilIdle().launched)
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

    @Test fun retryRequeuesBehindAlreadyWaitingPeer() {
        val service = service()
        service.enqueue(request("first"), priority = 50)
        service.enqueue(request("second"), priority = 50)
        val first = service.claim()!!
        assertEquals("first", first.id)
        service.finish(first.id, first.sessionId!!, "failed")
        service.retry("first")
        val second = service.claim()!!
        assertEquals("second", second.id)
        service.finish(second.id, second.sessionId!!, "succeeded")
        val retried = service.claim()!!
        assertEquals("first", retried.id)
        assertEquals(2, retried.attempt)
        assertNotEquals(first.sessionId, retried.sessionId)
    }

    @Test fun globalCancelInterruptsRunningSessionAndRejectsLateSuccess() {
        val service = service()
        service.enqueue(request("active"))
        val started = CountDownLatch(1)
        val scheduler = thread {
            TaskScheduler(service, TaskRunner { _, _ ->
                started.countDown()
                Thread.sleep(30000)
                "succeeded"
            }).runUntilIdle()
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            service.cancelAll()
        } finally {
            scheduler.join(5000)
        }
        assertFalse(scheduler.isAlive)
        assertEquals(TaskState.CANCELLED, service.show("active").state)
        assertEquals(0, service.show("active").transitions.count { it.to == TaskState.SUCCEEDED })
    }

    @Test fun anotherSchedulerCannotRecoverLiveTasksOrClaimConcurrently() {
        val service = service()
        service.enqueue(request("active"))
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val scheduler = thread {
            TaskScheduler(service, TaskRunner { _, _ ->
                started.countDown()
                gate.await(5, TimeUnit.SECONDS)
                "succeeded"
            }).runUntilIdle()
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFailsWith<IllegalStateException> {
                TaskScheduler(service(), schedulerTestRunner).runUntilIdle()
            }
            assertEquals(TaskState.RUNNING, service.show("active").state)
        } finally {
            gate.countDown()
            scheduler.join(5000)
        }
        assertFalse(scheduler.isAlive)
        assertEquals(TaskState.SUCCEEDED, service.show("active").state)
    }

    @Test fun retryDuringPauseJoinsBehindAlreadyReadyTasks() {
        val service = service()
        service.enqueue(request("older"), priority = 50)
        val first = service.claim()!!
        service.finish(first.id, first.sessionId!!, "failed")
        service.enqueue(request("waiting"), priority = 50)
        service.pause()
        service.retry("older")
        assertEquals(TaskState.PAUSED, service.show("older").state)
        service.resume()
        assertEquals("waiting", service.claim()!!.id)
        assertTrue(service.show("older").queueOrder > service.show("waiting").queueOrder)
    }

    @Test fun pausePreservesOrderOfAlreadyReadyTaskButNotNewArrival() {
        val service = service()
        service.enqueue(request("waiting"), priority = 50)
        val originalOrder = service.show("waiting").queueOrder
        service.pause()
        service.enqueue(request("newcomer"), priority = 50)
        service.resume()
        assertEquals(originalOrder, service.show("waiting").queueOrder)
        assertTrue(service.show("newcomer").queueOrder > originalOrder)
        assertEquals("waiting", service.claim()!!.id)
    }

    @Test fun dependencyAttentionMustBeResolvedThroughItsParent() {
        val service = service()
        service.enqueue(request("parent"))
        service.enqueue(request("child"), dependencies = listOf("parent"))
        service.cancel("parent")
        assertFailsWith<IllegalArgumentException> { service.retry("child") }
        assertEquals(TaskState.NEEDS_ATTENTION, service.show("child").state)
        service.retry("parent")
        assertEquals(TaskState.BLOCKED, service.show("child").state)
    }

    @Test fun cancelledAttemptCannotBeOverwrittenByImmediateRetryWhileWorkerIsAlive() {
        val service = service()
        service.enqueue(request("one"), priority = 50)
        val firstStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val starts = AtomicInteger()
        val activeCount = AtomicInteger()
        val peak = AtomicInteger()
        val scheduler = thread {
            TaskScheduler(service, TaskRunner { request, _ ->
                starts.incrementAndGet()
                val current = activeCount.incrementAndGet()
                peak.accumulateAndGet(current, ::maxOf)
                try {
                    if (request.invocation.getValue("attempt").jsonPrimitive.int == 1) {
                        firstStarted.countDown()
                        // Deliberately ignore interruption to exercise a slow provider shutdown.
                        while (releaseOld.count > 0) {
                            try { releaseOld.await(25, TimeUnit.MILLISECONDS) }
                            catch (_: InterruptedException) { }
                        }
                    }
                    "succeeded"
                } finally {
                    activeCount.decrementAndGet()
                }
            }, pollMillis = 10).runUntilIdle(concurrency = 2)
        }
        try {
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            service.cancel("one")
            service.retry("one")
            Thread.sleep(150)
            assertEquals(1, starts.get(), "The second attempt must wait for the old worker")
            assertEquals(1, peak.get())
        } finally {
            releaseOld.countDown()
            scheduler.join(5000)
        }
        assertFalse(scheduler.isAlive)
        assertEquals(2, starts.get())
        assertEquals(1, peak.get())
        assertEquals(2, service.show("one").attempt)
        assertEquals(TaskState.SUCCEEDED, service.show("one").state)
        assertEquals("session-one-2", service.show("one").sessionId)
    }

    @Test fun schedulerStopMarksInterruptedTaskNeedsAttentionNotCancelled() {
        val service = service()
        service.enqueue(request("one"))
        val started = CountDownLatch(1)
        val scheduler = TaskScheduler(service, TaskRunner { _, _ ->
            started.countDown()
            try { Thread.sleep(30000) } catch (_: InterruptedException) { }
            "cancelled"
        }, pollMillis = 10)
        val worker = thread { scheduler.runUntilIdle() }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            scheduler.requestStop()
        } finally {
            worker.join(5000)
        }
        assertFalse(worker.isAlive)
        assertEquals(TaskState.NEEDS_ATTENTION, service.show("one").state)
        assertTrue(service.show("one").reason!!.startsWith("scheduler stopped:"))
    }

    @Test fun unexpectedWorkerCrashStillMarksAttemptNeedsAttention() {
        val service = service()
        service.enqueue(request("one"))
        assertFailsWith<java.util.concurrent.ExecutionException> {
            TaskScheduler(service, TaskRunner { _, _ ->
                throw AssertionError("Simulated fatal agent failure")
            }).runUntilIdle()
        }
        assertEquals(TaskState.NEEDS_ATTENTION, service.show("one").state)
    }

    @Test fun idleClaimDoesNotRewriteDurableStoreSnapshot() {
        val service = service()
        service.enqueue(request("one"))
        val task = service.claim()!!
        service.finish(task.id, task.sessionId!!, "succeeded")
        val file = temp.resolve("store/tasks.json")
        val before = Files.getLastModifiedTime(file)
        Thread.sleep(80)
        repeat(8) { assertNull(service.claim()) }
        assertEquals(before, Files.getLastModifiedTime(file))
    }

    @Test fun transitionHistoryIsBoundedAndRecordsPrunedCount() {
        val service = service()
        service.enqueue(request("one"))
        repeat(70) {
            service.pause()
            service.resume()
        }
        val stored = service().show("one")
        assertEquals(128, stored.transitions.size)
        assertTrue(stored.prunedTransitions > 0)
        assertEquals(TaskState.READY, stored.state)
        assertEquals(stored.prunedTransitions.toInt(),
            stored.toPublicJson().getValue("prunedTransitions").jsonPrimitive.int)
    }



}

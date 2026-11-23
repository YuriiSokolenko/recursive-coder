package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir

// Scheduler unit tests use an injected, deterministic runner; cross-module execution is tested in :app:cli.
private val schedulerTestRunner = TaskRunner { _, _ -> "succeeded" }

@Tag("integration")
class TaskManagerTest {
    @TempDir lateinit var temp: Path

    private fun service() = TaskService(
        TaskStore(temp.resolve("store")),
        DirectoryTaskRunPaths.forStore(temp.resolve("store"))
    )
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

    @Test fun unknownStoredResultStatusRejectsAllReadsAndMutationsWithoutClobberingSnapshot() {
        val service = service()
        service.enqueue(request("one"))
        val path = temp.resolve("store/tasks.json")
        val original = Json.parseToJsonElement(Files.readString(path)).jsonObject
        val tamperedTasks = buildJsonArray {
            original.getValue("tasks").jsonArray.forEach { task ->
                add(JsonObject(task.jsonObject + ("lastResultStatus" to JsonPrimitive("bogus"))))
            }
        }
        val poisoned = JsonObject(original + ("tasks" to tamperedTasks)).toString()
        Files.writeString(path, poisoned)

        val failure = assertFailsWith<IllegalStateException> { service().list() }
        assertEquals("Corrupt task store; refusing to overwrite it", failure.message)
        assertEquals("Unsupported session result status", failure.cause?.message)
        assertFailsWith<IllegalStateException> { service().pause() }
        assertEquals(poisoned, Files.readString(path))
    }

    @Test fun schedulerConvertsUnknownRunnerStatusIntoDurableFailure() {
        val service = service()
        service.enqueue(request("one"))
        val report = TaskScheduler(service, TaskRunner { _, _ -> "secret-invalid-status" },
            pollMillis = 10).runUntilIdle()
        assertEquals(1, report.launched)
        assertEquals(1, report.completed)
        val persisted = service().show("one")
        assertEquals(TaskState.FAILED, persisted.state)
        assertEquals(SessionOutcome.FAILED, persisted.lastResultStatus)
        assertEquals(1, persisted.attempt)
        assertFalse(Files.readString(temp.resolve("store/tasks.json")).contains("secret-invalid-status"))
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

    @Test fun lateCancelledAttemptResultCannotOverrideRunningRetry() {
        // Cover both stale results on both sides of the new claim. Each case
        // owns a separate task so the test needs no scheduler or clock delays.
        for (status in listOf("failed", "succeeded")) {
            for (claimBeforeLateFinish in listOf(false, true)) {
                val id = status + "-" + (if (claimBeforeLateFinish) "running" else "ready")
                val service = service()
                service.enqueue(request(id))
                val first = service.claim()!!
                service.cancel(id)
                service.retry(id)

                val second = if (claimBeforeLateFinish) service.claim()!! else null
                service.finish(id, first.sessionId!!, status)

                val snapshot = service.show(id)
                assertEquals(if (claimBeforeLateFinish) TaskState.RUNNING else TaskState.READY,
                    snapshot.state, "Stale " + status + " before/after claim")
                assertEquals(second?.sessionId, snapshot.sessionId)
                assertEquals(if (claimBeforeLateFinish) 2 else 1, snapshot.attempt)
                assertEquals(0, snapshot.transitions.count {
                    it.to == TaskState.SUCCEEDED || it.to == TaskState.FAILED
                }, "Stale result must not add a terminal transition")

                val retry = second ?: service.claim()!!
                assertEquals(2, retry.attempt)
                assertNotEquals(first.sessionId, retry.sessionId)
                assertEquals(TaskState.RUNNING, service.show(id).state)

                service.finish(id, retry.sessionId!!, "succeeded")
                val completed = service.show(id)
                assertEquals(TaskState.SUCCEEDED, completed.state)
                assertEquals(retry.sessionId, completed.sessionId)
                assertEquals(2, completed.attempt)
                assertEquals(1, completed.transitions.count { it.to == TaskState.SUCCEEDED })
                assertEquals(0, completed.transitions.count { it.to == TaskState.FAILED })
            }
        }
    }

    @RepeatedTest(20)
    fun cancelledAttemptCannotBeOverwrittenByImmediateRetryWhileWorkerIsAlive() {
        val service = service()
        service.enqueue(request("one"), priority = 50)
        val firstStarted = CountDownLatch(1)
        val claimWhileOldWorkerAlive = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val starts = AtomicInteger()
        val activeCount = AtomicInteger()
        val peak = AtomicInteger()
        val oldTaskExcluded = AtomicBoolean(true)
        val claimedWhileOldWorkerAlive = AtomicBoolean(false)

        val runner = TaskRunner { request, _ ->
            val attempt = request.invocation.getValue("attempt").jsonPrimitive.int
            starts.incrementAndGet()
            val current = activeCount.incrementAndGet()
            peak.accumulateAndGet(current, ::maxOf)
            try {
                when (attempt) {
                    1 -> {
                        firstStarted.countDown()
                        // Model a provider that ignores cancellation until explicitly released.
                        while (releaseOld.count > 0) {
                            try { releaseOld.await() }
                            catch (_: InterruptedException) { }
                        }
                    }
                    2 -> {
                        secondStarted.countDown()
                        releaseSecond.await()
                    }
                    else -> error("Unexpected attempt: $attempt")
                }
                "succeeded"
            } finally {
                activeCount.decrementAndGet()
            }
        }

        // Observe a real scheduler claim after retry, while the first worker is
        // provably alive. This replaces a scheduler-timing-dependent sleep.
        val delegateClaim = DefaultClaimNextTaskUseCase(service.store)
        val observedClaim = ClaimNextTaskUseCase { excludedTaskIds ->
            val retryReadyWhileOldAlive = activeCount.get() == 1 &&
                service.show("one").state == TaskState.READY
            val claimed = delegateClaim(excludedTaskIds)
            if (retryReadyWhileOldAlive) {
                // Sticky failures: a later successful observation cannot erase a race.
                if ("one" !in excludedTaskIds) oldTaskExcluded.set(false)
                if (claimed != null) claimedWhileOldWorkerAlive.set(true)
                claimWhileOldWorkerAlive.countDown()
            }
            claimed
        }
        // The compatibility TaskService constructor wires these Default* use cases;
        // only claim is decorated here to observe the scheduler interleaving.
        // Keep this assembly aligned with TaskService if its wiring changes.
        val schedulerExecutor = Executors.newSingleThreadExecutor()
        val outcome = schedulerExecutor.submit<SchedulerReport> {
            TaskScheduler(
                repository = service.store,
                queries = DefaultTaskQueriesUseCase(service.store),
                claim = observedClaim,
                complete = DefaultCompleteTaskAttemptUseCase(service.store),
                interrupt = DefaultInterruptTaskAttemptUseCase(service.store),
                recover = DefaultRecoverInterruptedAttemptsUseCase(service.store),
                prepare = DefaultPrepareTaskAttemptUseCase(DirectoryTaskRunPaths.forStore(temp.resolve("store"))),
                runner = runner,
                pollMillis = 10
            ).runUntilIdle(concurrency = 2)
        }
        try {
            assertTrue(firstStarted.await(10, TimeUnit.SECONDS), "First worker did not start")
            service.cancel("one")
            service.retry("one")

            assertTrue(claimWhileOldWorkerAlive.await(10, TimeUnit.SECONDS),
                "Scheduler did not evaluate the retry while the old worker was alive")
            assertTrue(oldTaskExcluded.get(), "Old worker must exclude its task from claims")
            assertFalse(claimedWhileOldWorkerAlive.get(), "Retry must not be claimed yet")
            assertEquals(1, starts.get())
            assertEquals(1, peak.get())
            assertEquals(TaskState.READY, service.show("one").state)

            releaseOld.countDown()
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS),
                "Retry did not launch after old worker termination")
            val runningRetry = service.show("one")
            assertEquals(2, starts.get())
            assertEquals(1, peak.get(), "Old and retried workers must never overlap")
            assertEquals(TaskState.RUNNING, runningRetry.state)
            assertEquals(2, runningRetry.attempt)
            assertEquals("session-one-2", runningRetry.sessionId)
            assertEquals(0, runningRetry.transitions.count { it.to == TaskState.SUCCEEDED },
                "Old worker success must not terminalize the new attempt")

            assertTrue(oldTaskExcluded.get(), "Task exclusion must hold on every observed claim")
            assertFalse(claimedWhileOldWorkerAlive.get(),
                "No claim may succeed while the old worker remains active")
            releaseSecond.countDown()
            val report = outcome.get(15, TimeUnit.SECONDS)
            assertEquals(2, report.launched)
            assertEquals(2, report.completed)
            val completed = service.show("one")
            assertEquals(TaskState.SUCCEEDED, completed.state)
            assertEquals(2, completed.attempt)
            assertEquals("session-one-2", completed.sessionId)
            assertEquals(1, completed.transitions.count { it.to == TaskState.SUCCEEDED })
        } finally {
            releaseOld.countDown()
            releaseSecond.countDown()
            schedulerExecutor.shutdownNow()
            assertTrue(schedulerExecutor.awaitTermination(15, TimeUnit.SECONDS),
                "Scheduler must release its worker pool and exclusive store lock")
        }
    }

    @Test fun cancellationAfterRunnerExitCannotInterruptDurableCompletion() {
        val service = service()
        service.enqueue(request("completed"))
        val store = service.store as TaskStore
        val runningCancellation = AtomicReference<Cancellation?>()
        val durabilityHookObserved = CountDownLatch(1)
        val lateCancellationTimedOut = AtomicBoolean(false)
        val interruptedDuringSync = AtomicBoolean(false)

        // The worker's complete() transaction calls this hook immediately before
        // directory fsync. Capture observations without asserting inside TaskStore.
        store.directorySyncTestHook = {
            val cancellation = runningCancellation.get()
            if (cancellation != null) {
                val cancellingThread = thread(name = "late-cancellation") { cancellation.cancel() }
                cancellingThread.join(5000)
                // Keep any failure sticky across multiple directory-sync hook calls.
                if (cancellingThread.isAlive) lateCancellationTimedOut.set(true)
                if (Thread.currentThread().isInterrupted) interruptedDuringSync.set(true)
                durabilityHookObserved.countDown()
            }
        }
        try {
            val outcome = runCatching {
                TaskScheduler(service, TaskRunner { _, cancellation ->
                    runningCancellation.set(cancellation)
                    "succeeded"
                }, pollMillis = 10).runUntilIdle()
            }
            assertTrue(durabilityHookObserved.await(5, TimeUnit.SECONDS),
                "Durable completion hook was not observed")
            assertFalse(lateCancellationTimedOut.get(), "Late cancellation did not finish")
            assertFalse(interruptedDuringSync.get(),
                "Cancellation reached the worker after runner completion")
            val report = outcome.getOrThrow()
            assertEquals(1, report.launched)
            assertEquals(1, report.completed)
            assertEquals(TaskState.SUCCEEDED, service.show("completed").state)
        } finally {
            store.directorySyncTestHook = null
        }
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
        val disk = Json.parseToJsonElement(Files.readString(temp.resolve("store/tasks.json"))).jsonObject
        val persistedTask = disk.getValue("tasks").jsonArray.single().jsonObject
        assertEquals(stored.prunedTransitions,
            persistedTask.getValue("prunedTransitions").jsonPrimitive.long)
    }

    @Test fun taskServiceRequestForPreservesV1OutputLayoutAcrossAttempts() {
        val service = service()
        service.enqueue(request("layout"))
        val first = service.claim()!!
        val firstRoot = temp.resolve("store/runs/layout/attempt-1").toAbsolutePath().normalize()
        val firstRequest = service.requestFor(first)
        assertEquals(firstRoot, firstRequest.outputRoot)
        assertEquals(firstRoot.resolve("result.json"), firstRequest.output("resultPath"))
        assertEquals(firstRoot.resolve("events.jsonl"), firstRequest.output("eventsPath"))
        assertEquals(firstRoot.resolve("diagnostics.json"), firstRequest.output("diagnosticsPath"))
        assertEquals(firstRoot.resolve("checkpoints"), firstRequest.output("checkpointDir"))
        service.finish(first.id, first.sessionId!!, "failed")
        service.retry(first.id)
        val second = service.claim()!!
        assertEquals(2, second.attempt)
        val secondRoot = temp.resolve("store/runs/layout/attempt-2").toAbsolutePath().normalize()
        assertEquals(secondRoot, service.requestFor(second).outputRoot)
    }

    @Test fun schedulerRejectsTamperedTaskTemplatesAtMappingBoundary() {
        val service = service()
        val bad = Task(id = "task", requestTemplate = "{}",
            dependencies = emptyList(), priority = 50,
            state = TaskState.RUNNING, attempt = 1, sessionId = "session-task-1")
        assertFailsWith<Refused> { service.requestFor(bad) }
        assertFailsWith<IllegalArgumentException> {
            service.requestFor(bad.copy(state = TaskState.READY))
        }
    }

    @Test fun scheduledAttemptRejectsValidTemplateBelongingToAnotherTask() {
        val service = service()
        service.enqueue(request("original"))
        val running = service.claim()!!
        val foreignTemplate = request("foreign")
        val failure = assertFailsWith<IllegalArgumentException> {
            service.requestFor(running.copy(requestTemplate = foreignTemplate))
        }
        assertEquals("Task template ID mismatch", failure.message)
        assertEquals(TaskState.RUNNING, service.show("original").state)
    }

    @Test fun invalidRunnerResultIsRejectedBeforeAnyPersistentMutation() {
        val service = service()
        service.enqueue(request("one"))
        val running = service.claim()!!
        val snapshot = Files.readString(temp.resolve("store/tasks.json"))
        val failure = assertFailsWith<IllegalArgumentException> {
            service.finish(running.id, running.sessionId!!, "secret-user-text")
        }
        assertEquals("Unsupported session result status", failure.message)
        assertEquals(snapshot, Files.readString(temp.resolve("store/tasks.json")))
        assertEquals(TaskState.RUNNING, service.show("one").state)
    }

}

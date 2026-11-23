package ai.yuriilab.recursivecoder

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Run the same real TaskService/TaskPolicy decisions against both repository adapters. */
@Tag("integration")
class TaskRepositoryContractTest {
    @TempDir lateinit var temp: Path

    @Test fun adaptersRollbackAndAtomicallyClaimCancelRetryAndIgnoreStaleFinish() {
        val results = listOf(
            "memory" to InMemoryTaskRepository(),
            "file" to TaskStore(temp.resolve("store"))
        ).map { (name, repo) ->
            val service = TaskService(repo)
            val initial = Task(
                id = "only", requestTemplate = "{}", dependencies = emptyList(),
                priority = 50, state = TaskState.READY
            )
            repo.update { TaskMutation(it.copy(tasks = listOf(initial)), Unit) }
            assertFailsWith<IllegalArgumentException>(name) {
                repo.update<Int> { throw IllegalArgumentException("rejected transaction") }
            }
            assertEquals(initial, service.show("only"), name)

            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(8)
            val winners = try {
                val jobs = (0 until 8).map {
                    pool.submit<Task?> {
                        start.await()
                        service.claim()
                    }
                }
                start.countDown()
                jobs.map { it.get(5, TimeUnit.SECONDS) }.filterNotNull()
            } finally {
                pool.shutdownNow()
            }
            assertEquals(1, winners.size, name)
            val first = winners.single()
            assertEquals(1, first.attempt, name)
            assertEquals(TaskState.RUNNING, service.show("only").state, name)

            val cancelled = service.cancel("only")
            assertEquals(TaskState.CANCELLED, cancelled.state, name)
            // Invoke the REAL finish policy, not a hand-written version in the test.
            assertEquals(cancelled, service.finish("only", first.sessionId!!, "succeeded"), name)
            assertEquals(TaskState.CANCELLED, service.show("only").state, name)

            service.retry("only")
            val second = assertNotNull(service.claim(), name)
            assertEquals(2, second.attempt, name)
            assertNotEquals(first.sessionId, second.sessionId, name)
            // Old generation cannot finish the newly-claimed attempt.
            assertEquals(second, service.finish("only", first.sessionId!!, "succeeded"), name)
            val finished = service.finish("only", second.sessionId!!, "succeeded")
            assertEquals(TaskState.SUCCEEDED, finished.state, name)
            name to Triple(finished.state, finished.attempt, finished.sessionId)
        }
        assertEquals(results[0].second, results[1].second)
        assertEquals(TaskState.SUCCEEDED, results[0].second.first)
        assertEquals(2, results[0].second.second)
    }

    @Test fun atomicRenameFailurePreservesPreviousSnapshotAndDoesNotTrustOrphanTemp() {
        val repo = TaskStore(temp.resolve("rename-failure"))
        repo.update { TaskMutation(it.copy(paused = true), Unit) }
        val snapshot = repo.directory.resolve("tasks.json")
        val backup = repo.directory.resolve("original.snapshot")
        val before = Files.readAllBytes(snapshot)
        try {
            assertFailsWith<IOException> {
                repo.update { current ->
                    // The old snapshot was read under the OS lock. Force the destination
                    // to reject replacing a directory with the newly fsynced temp file.
                    Files.move(snapshot, backup)
                    Files.createDirectory(snapshot)
                    TaskMutation(current.copy(paused = false), Unit)
                }
            }
        } finally {
            if (Files.exists(backup)) {
                Files.deleteIfExists(snapshot)
                Files.move(backup, snapshot)
            }
        }
        assertContentEquals(before, Files.readAllBytes(snapshot))
        assertTrue(repo.read { it.paused }, "The abandoned .tmp must not be considered authoritative")
        repo.update { TaskMutation(it.copy(paused = false), Unit) }
        assertFalse(TaskStore(repo.directory).read { it.paused })
    }

    /** Issue #162: a failed directory fsync after the atomic rename is never silent. */
    @Test fun directoryFsyncFailureAfterRenameFailsTheTransactionOnLinux() {
        val store = TaskStore(temp.resolve("fsync-store"))
        val initial = Task(id = "durable", requestTemplate = "{}", dependencies = emptyList(),
            priority = 1, state = TaskState.READY)
        store.update { TaskMutation(it.copy(tasks = listOf(initial)), Unit) }
        val synced = mutableListOf<Path>()
        store.directorySyncTestHook = {
            synced.add(it)
            throw IOException("induced directory fsync failure")
        }
        val changed = initial.copy(priority = 2)
        val linux = System.getProperty("os.name").startsWith("Linux")
        if (linux) {
            val failure = assertFailsWith<IOException> {
                store.update { TaskMutation(it.copy(tasks = listOf(changed)), Unit) }
            }
            assertEquals("induced directory fsync failure", failure.cause?.message)
        } else {
            store.update { TaskMutation(it.copy(tasks = listOf(changed)), Unit) }
        }
        assertEquals(listOf(store.directory), synced)
        // The rename already happened: the new snapshot is visible but was reported
        // as not durable instead of silently claiming a durable commit.
        store.directorySyncTestHook = null
        assertEquals(changed, store.read { queue -> queue.tasks.single() })
        assertFalse(Files.exists(store.directory.resolve("tasks.json.tmp")))
    }

    @Test fun staleInterruptCannotMutateNewerPersistedAttempt() {
        val repo = TaskStore(temp.resolve("stale-interrupt"))
        val initial = Task(id = "only", requestTemplate = "{}", dependencies = emptyList(),
            priority = 50, state = TaskState.READY)
        repo.update { TaskMutation(it.copy(tasks = listOf(initial)), Unit) }
        val claim = DefaultClaimNextTaskUseCase(repo)
        val interrupt = DefaultInterruptTaskAttemptUseCase(repo)
        val retry = DefaultRetryTaskUseCase(repo)

        val first = assertNotNull(claim(emptySet()))
        interrupt(first.id, first.sessionId!!, "scheduler stopped")
        retry("only")
        val newer = assertNotNull(claim(emptySet()))
        assertEquals(2, newer.attempt)
        val before = repo.read { it }
        val snapshot = repo.directory.resolve("tasks.json")
        val beforeBytes = Files.readAllBytes(snapshot)

        assertEquals(newer, interrupt("only", first.sessionId!!, "stale interruption"))
        assertEquals(before, repo.read { it },
            "Stale interruption must preserve the full queue snapshot")
        assertContentEquals(beforeBytes, Files.readAllBytes(snapshot),
            "Stale interruption must not rewrite the durable snapshot")
    }

    @Test fun durableRecoveryDeniesMissingForeignAndExpiredSchedulerLeases() {
        val root = temp.resolve("recovery-lease")
        val first = TaskStore(root)
        val second = TaskStore(root)
        val initial = Task(id = "only", requestTemplate = "{}", dependencies = emptyList(),
            priority = 50, state = TaskState.READY)
        first.update { TaskMutation(it.copy(tasks = listOf(initial)), Unit) }
        assertNotNull(DefaultClaimNextTaskUseCase(first)(emptySet()))
        val recovery = DefaultRecoverInterruptedAttemptsUseCase(first)
        val foreignRecovery = DefaultRecoverInterruptedAttemptsUseCase(second)
        val before = first.read { it }
        val file = root.resolve("tasks.json")
        val bytes = Files.readAllBytes(file)

        val missing = assertFailsWith<IllegalStateException> { recovery() }
        assertEquals("Scheduler lease required for recovery", missing.message)
        val foreign = assertFailsWith<IllegalStateException> { foreignRecovery() }
        assertEquals("Scheduler lease required for recovery", foreign.message)
        assertEquals(before, first.read { it })
        assertContentEquals(bytes, Files.readAllBytes(file))

        val pool = Executors.newSingleThreadExecutor()
        try {
            first.withSchedulerLock {
                val foreign = assertFailsWith<IllegalStateException> { foreignRecovery() }
                assertEquals("Scheduler lease required for recovery", foreign.message)
                val competing = assertFailsWith<IllegalStateException> {
                    second.withSchedulerLock { error("Competing scheduler acquired OS lock") }
                }
                assertEquals("Another scheduler is active", competing.message)
                pool.submit {
                    val wrongThread = assertFailsWith<IllegalStateException> { recovery() }
                    assertEquals("Scheduler lease required for recovery", wrongThread.message)
                }.get(5, TimeUnit.SECONDS)
                assertEquals(before, first.read { it })
                recovery()
                assertEquals(TaskState.NEEDS_ATTENTION, first.read { it.tasks.single().state })
            }
        } finally {
            pool.shutdownNow()
        }
        val expired = assertFailsWith<IllegalStateException> { recovery() }
        assertEquals("Scheduler lease required for recovery", expired.message)
        // The same authoritative OS lease can subsequently be acquired by another instance.
        second.withSchedulerLock {
            foreignRecovery()
            assertEquals(TaskState.NEEDS_ATTENTION, second.read { it.tasks.single().state })
        }
    }

    @Test fun differentFileStoreInstancesCannotHoldSchedulerLeaseSimultaneously() {
        val root = temp.resolve("scheduler-lease")
        val first = TaskStore(root)
        val second = TaskStore(root)
        first.withSchedulerLock {
            assertFailsWith<IllegalStateException> {
                second.withSchedulerLock { error("Should not enter a second scheduler") }
            }
        }
        second.withSchedulerLock { assertFalse(second.read { it.paused }) }
    }

    @Test fun schedulerLeaseFromAnotherProcessCannotBeBypassedByRecovery() {
        val repo = TaskStore(temp.resolve("external-scheduler"))
        val initial = Task(id = "running", requestTemplate = "{}", dependencies = emptyList(),
            priority = 50, state = TaskState.READY)
        repo.update { TaskMutation(it.copy(tasks = listOf(initial)), Unit) }
        assertNotNull(DefaultClaimNextTaskUseCase(repo)(emptySet()))
        val recover = DefaultRecoverInterruptedAttemptsUseCase(repo)
        val before = repo.read { it }
        val beforeBytes = Files.readAllBytes(repo.directory.resolve("tasks.json"))
        val script = """
import fcntl, sys
with open(sys.argv[1], 'a+b', buffering=0) as guard:
    fcntl.lockf(guard, fcntl.LOCK_EX)
    print('LOCKED', flush=True)
    sys.stdin.readline()
""".trimIndent()
        val child = ProcessBuilder("python3", "-u", "-c", script,
            repo.directory.resolve(".scheduler.lock").toString()).start()
        try {
            assertEquals("LOCKED", child.inputStream.bufferedReader().readLine())
            val contended = assertFailsWith<IllegalStateException> {
                repo.withSchedulerLock { recover() }
            }
            assertEquals("Another scheduler is active", contended.message)
            val missing = assertFailsWith<IllegalStateException> { recover() }
            assertEquals("Scheduler lease required for recovery", missing.message)
            assertEquals(before, repo.read { it })
            assertContentEquals(beforeBytes, Files.readAllBytes(repo.directory.resolve("tasks.json")))
            child.outputStream.bufferedWriter().use { it.write("\n"); it.flush() }
            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Fixture process did not exit")
            assertEquals(0, child.exitValue())
        } finally {
            child.destroyForcibly()
            child.waitFor(5, TimeUnit.SECONDS)
        }
        repo.withSchedulerLock { recover() }
        assertEquals(TaskState.NEEDS_ATTENTION, repo.read { it.tasks.single().state })
    }

    @Test fun osLockHeldBySecondProcessBlocksTaskTransaction() {
        val repo = TaskStore(temp.resolve("cross-process"))
        repo.read { it.tasks.size } // creates .store.lock
        val script = """
import fcntl, sys
with open(sys.argv[1], 'a+b', buffering=0) as guard:
    fcntl.lockf(guard, fcntl.LOCK_EX)
    print('LOCKED', flush=True)
    sys.stdin.readline()
""".trimIndent()
        val child = ProcessBuilder("python3", "-u", "-c", script,
            repo.directory.resolve(".store.lock").toString()).start()
        val pool = Executors.newSingleThreadExecutor()
        try {
            assertEquals("LOCKED", child.inputStream.bufferedReader().readLine())
            val future = pool.submit<Int> { repo.read { it.tasks.size } }
            Thread.sleep(200)
            assertFalse(future.isDone, "A concurrent JVM transaction bypassed the OS lock")
            child.outputStream.bufferedWriter().use { it.write("\n"); it.flush() }
            assertEquals(0, future.get(5, TimeUnit.SECONDS))
            assertEquals(0, child.waitFor())
        } finally {
            child.destroyForcibly()
            pool.shutdownNow()
        }
    }
}

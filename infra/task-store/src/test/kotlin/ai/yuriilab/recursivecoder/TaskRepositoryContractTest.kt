package ai.yuriilab.recursivecoder

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Run the same real TaskService/TaskPolicy decisions against both repository adapters. */
@Tag("integration")
class TaskRepositoryContractTest {
    @TempDir lateinit var temp: Path

    private data class LockFixture(val process: Process, val stderr: Path) {
        fun diagnostics(): String {
            val status = if (process.isAlive) "running" else "exit=${process.exitValue()}"
            val error = runCatching { Files.readString(stderr) }.getOrElse { "Cannot read stderr: $it" }
            return "JVM fixture ($status), stderr:\n$error"
        }
    }

    /** Quote one Java launcher argfile token, including Windows backslashes. */
    private fun argfileToken(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
            .replace("\u000c", "\\f") + "\""

    /** Run lock probes in an independent JVM, portable across POSIX and Windows. */
    private fun launchLockFixture(mode: String, path: Path): LockFixture {
        val classpath = System.getProperty("taskstore.test.runtime.classpath")
            ?: System.getProperty("java.class.path")
        require(!classpath.isNullOrBlank()) { "Missing JVM fixture runtime classpath" }
        val executable = Path.of(System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val args = Files.createTempFile(temp, "fixture-", ".args")
        val stderr = Files.createTempFile(temp, "fixture-", ".err")
        Files.writeString(args, listOf("-cp", classpath,
            TaskStoreLockProcessFixture::class.java.name, mode, path.toString())
            .joinToString("\n", postfix = "\n", transform = ::argfileToken),
            java.nio.charset.Charset.defaultCharset())
        val process = ProcessBuilder(executable.toString(), "@$args")
            .redirectError(stderr.toFile()).start()
        return LockFixture(process, stderr)
    }

    /** A child prints its state only after the OS lock call has completed. */
    private fun awaitFixtureLine(child: LockFixture, expected: String) {
        val reader = Executors.newSingleThreadExecutor()
        try {
            val line = try {
                reader.submit<String?> {
                    child.process.inputStream.bufferedReader().use { it.readLine() }
                }.get(5, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                throw AssertionError("Timed out awaiting $expected. ${child.diagnostics()}", e)
            }
            assertEquals(expected, line, child.diagnostics())
        } finally {
            // Interrupting readLine does not unblock a pipe read; the caller's
            // finally stops the child, closing the pipe even on timeout.
            reader.shutdownNow()
        }
    }

    private fun awaitFixtureExit(child: LockFixture) {
        assertTrue(child.process.waitFor(5, TimeUnit.SECONDS), child.diagnostics())
        assertEquals(0, child.process.exitValue(), child.diagnostics())
    }

    private fun releaseFixture(child: LockFixture) {
        try {
            child.process.outputStream.bufferedWriter().use {
                it.write("RELEASE\n")
                it.flush()
            }
        } catch (e: IOException) {
            throw AssertionError(child.diagnostics(), e)
        }
        awaitFixtureExit(child)
    }

    private fun stopFixture(child: LockFixture) {
        child.process.destroyForcibly()
        child.process.waitFor(5, TimeUnit.SECONDS)
    }

    private fun assertOsLockUnavailableToAnotherProcess(path: Path) {
        val child = launchLockFixture("probe", path)
        try {
            awaitFixtureLine(child, "BUSY")
            awaitFixtureExit(child)
        } finally {
            stopFixture(child)
        }
    }

    private fun symlinkDirectoryOrSkip(alias: Path, target: Path) {
        try {
            Files.createSymbolicLink(alias, target)
        } catch (e: UnsupportedOperationException) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "Directory symlinks are unavailable on this filesystem: $e")
        } catch (e: java.nio.file.FileSystemException) {
            if (!System.getProperty("os.name").startsWith("Windows")) throw e
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "Directory symlinks require Windows privilege or developer mode: $e")
        }
    }

    @Test fun forkedFixtureReportsStderrAndSupportsQuotedPaths() {
        val root = Files.createDirectory(temp.resolve("lock path with spaces #"))
        val child = launchLockFixture("invalid-mode", root.resolve(".store.lock"))
        try {
            val failure = assertFailsWith<AssertionError> { awaitFixtureLine(child, "LOCKED") }
            assertTrue(failure.message.orEmpty().contains("Unknown JVM lock fixture mode: invalid-mode"),
                failure.message)
            assertTrue(child.process.waitFor(5, TimeUnit.SECONDS))
            assertNotEquals(0, child.process.exitValue())
        } finally {
            stopFixture(child)
        }
        val probe = launchLockFixture("probe", root.resolve(".store.lock"))
        try {
            awaitFixtureLine(probe, "ACQUIRED")
            awaitFixtureExit(probe)
        } finally {
            stopFixture(probe)
        }
    }

    @Test fun adaptersRollbackAndAtomicallyClaimCancelRetryAndIgnoreStaleFinish() {
        val results = listOf(
            "memory" to InMemoryTaskRepository(),
            "file" to TaskStore(temp.resolve("store"))
        ).map { (name, repo) ->
            val service = TaskService(repo, DirectoryTaskRunPaths(temp.resolve("contract-runs/$name")))
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
        val orphan = repo.directory.resolve("tasks.json.tmp")
        val before = Files.readAllBytes(snapshot)
        var reachedReplacement = false
        // Failure injection happens only after writing, force(true) and closing
        // the new temp file; filesystem-specific directory rename behavior is irrelevant.
        repo.testHooks.atomicReplaceFailure = { source, destination ->
            reachedReplacement = true
            assertEquals(orphan, source)
            assertEquals(snapshot, destination)
            assertContentEquals(before, Files.readAllBytes(destination))
            assertTrue(Files.isRegularFile(source), "Fsynced replacement file must exist")
            assertEquals(JsonPrimitive(false),
                Json.parseToJsonElement(Files.readString(source)).jsonObject["paused"],
                "Replacement must contain the updated snapshot")
            throw IOException("injected atomic replacement failure")
        }
        try {
            val failure = assertFailsWith<IOException> {
                repo.update { TaskMutation(it.copy(paused = false), Unit) }
            }
            assertEquals("injected atomic replacement failure", failure.message)
        } finally {
            repo.testHooks.atomicReplaceFailure = null
        }
        assertTrue(reachedReplacement, "Atomic replacement boundary must be reached")
        assertContentEquals(before, Files.readAllBytes(snapshot))
        assertTrue(Files.isRegularFile(orphan), "The failed move must leave an orphan temp")
        assertTrue(TaskStore(repo.directory).read { it.paused },
            "An orphan temp must not override the authoritative snapshot")
        repo.update { TaskMutation(it.copy(paused = false), Unit) }
        assertFalse(TaskStore(repo.directory).read { it.paused })
        assertFalse(Files.exists(orphan), "Successful replacement must consume the temp")
    }

    /** Issue #162: a failed directory fsync after the atomic rename is never silent. */
    @Test fun directoryFsyncFailureAfterRenameFailsTheTransactionOnLinux() {
        val store = TaskStore(temp.resolve("fsync-store"))
        val initial = Task(id = "durable", requestTemplate = "{}", dependencies = emptyList(),
            priority = 1, state = TaskState.READY)
        store.update { TaskMutation(it.copy(tasks = listOf(initial)), Unit) }
        val synced = mutableListOf<Path>()
        store.testHooks.directorySync = {
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
        store.testHooks.directorySync = null
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

    @Test fun aliasedTaskStoresSerializeTransactionsWithoutReleasingOsFileLock() {
        val realParent = temp.resolve("transaction-real-parent")
        Files.createDirectories(realParent)
        val aliasParent = temp.resolve("transaction-parent-alias")
        symlinkDirectoryOrSkip(aliasParent, realParent)
        val primary = TaskStore(realParent.resolve("queue"))
        val alias = TaskStore(aliasParent.resolve("queue"))
        val start = CountDownLatch(1)
        val entering = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val contender = pool.submit<Boolean> {
                start.await()
                entering.countDown()
                alias.read { it.paused }
            }
            primary.read { queue ->
                val nested = assertFailsWith<IllegalStateException> {
                    primary.read { it.tasks.size }
                }
                assertEquals("Nested task store transaction is not allowed", nested.message)
                start.countDown()
                assertTrue(entering.await(5, TimeUnit.SECONDS), "Contender never started")
                assertFailsWith<java.util.concurrent.TimeoutException> {
                    contender.get(200, TimeUnit.MILLISECONDS)
                }
                assertOsLockUnavailableToAnotherProcess(primary.directory.resolve(".store.lock"))
                assertFalse(queue.paused)
            }
            assertFalse(contender.get(5, TimeUnit.SECONDS),
                "The aliased transaction must finish after the original releases its OS lock")
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test fun failedJvmSchedulerAcquisitionCannotReleaseOriginalOsLease() {
        val realParent = temp.resolve("real-parent")
        Files.createDirectories(realParent)
        val aliasParent = temp.resolve("alias-parent")
        symlinkDirectoryOrSkip(aliasParent, realParent)
        val root = realParent.resolve("queue")
        val first = TaskStore(root)
        val second = TaskStore(root)
        // Parent-path aliases must share the same JVM ownership guard.
        val aliased = TaskStore(aliasParent.resolve("queue"))
        val pool = Executors.newSingleThreadExecutor()
        try {
            first.withSchedulerLock {
                for (contender in listOf(first, second, aliased)) {
                    val denied = assertFailsWith<IllegalStateException> {
                        contender.withSchedulerLock { error("Overlapping scheduler entered") }
                    }
                    assertEquals("Another scheduler is active", denied.message)
                }
                pool.submit {
                    val denied = assertFailsWith<IllegalStateException> {
                        second.withSchedulerLock { error("Competing thread entered") }
                    }
                    assertEquals("Another scheduler is active", denied.message)
                }.get(5, TimeUnit.SECONDS)

                // A failed same-JVM acquisition must not close a second channel
                // and silently drop the original JVM's POSIX scheduler lock.
                assertOsLockUnavailableToAnotherProcess(root.resolve(".scheduler.lock"))
                first.requireSchedulerLockHeld()
            }
        } finally {
            pool.shutdownNow()
        }
        // The JVM guard must be released together with the OS lock.
        aliased.withSchedulerLock {
            aliased.requireSchedulerLockHeld()
        }
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
        val child = launchLockFixture("hold", repo.directory.resolve(".scheduler.lock"))
        try {
            awaitFixtureLine(child, "LOCKED")
            val contended = assertFailsWith<IllegalStateException> {
                repo.withSchedulerLock { recover() }
            }
            assertEquals("Another scheduler is active", contended.message)
            val missing = assertFailsWith<IllegalStateException> { recover() }
            assertEquals("Scheduler lease required for recovery", missing.message)
            assertEquals(before, repo.read { it })
            assertContentEquals(beforeBytes, Files.readAllBytes(repo.directory.resolve("tasks.json")))
            releaseFixture(child)
        } finally {
            stopFixture(child)
        }
        repo.withSchedulerLock { recover() }
        assertEquals(TaskState.NEEDS_ATTENTION, repo.read { it.tasks.single().state })
    }

    @Test fun osLockHeldBySecondProcessBlocksTaskTransaction() {
        val repo = TaskStore(temp.resolve("cross-process"))
        repo.read { it.tasks.size } // creates .store.lock
        val child = launchLockFixture("hold", repo.directory.resolve(".store.lock"))
        val reachedAcquisition = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            awaitFixtureLine(child, "LOCKED")
            // The handshake is inside transaction(): after its JVM mutex and
            // channel opening, immediately before the blocking OS lock call.
            repo.testHooks.beforeStoreLockAcquisition = { reachedAcquisition.countDown() }
            val future = pool.submit<Int> { repo.read { it.tasks.size } }
            assertTrue(reachedAcquisition.await(5, TimeUnit.SECONDS),
                "Contender never reached FileChannel.lock()")
            assertFailsWith<java.util.concurrent.TimeoutException> {
                future.get(300, TimeUnit.MILLISECONDS)
            }
            releaseFixture(child)
            assertEquals(0, future.get(5, TimeUnit.SECONDS))
        } finally {
            repo.testHooks.beforeStoreLockAcquisition = null
            stopFixture(child)
            pool.shutdownNow()
        }
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
    }
}

/** Standalone JVM entry point for portable cross-process FileChannel lock checks. */
object TaskStoreLockProcessFixture {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "Expected mode and OS lock path" }
        val path = Path.of(args[1])
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            when (args[0]) {
                "probe" -> {
                    val lock = channel.tryLock()
                    if (lock == null) println("BUSY")
                    else lock.use { println("ACQUIRED") }
                }
                "hold" -> channel.lock().use {
                    println("LOCKED")
                    check(System.`in`.bufferedReader().readLine() == "RELEASE") {
                        "Parent did not authorize fixture lock release"
                    }
                }
                else -> error("Unknown JVM lock fixture mode: ${args[0]}")
            }
        }
    }
}

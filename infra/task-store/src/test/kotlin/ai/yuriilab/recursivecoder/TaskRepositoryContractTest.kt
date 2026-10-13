package ai.yuriilab.recursivecoder

import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The identical operations must be observable for both persistence adapters. */
@Tag("integration")
class TaskRepositoryContractTest {
    @TempDir lateinit var temp: Path

    @Test fun adaptersCommitRollbackAndSerializeClaimsTheSameWay() {
        val results = listOf(
            "memory" to InMemoryTaskRepository(),
            "file" to TaskStore(temp.resolve("store"))
        ).map { (name, repo) ->
            val initial = Task(id = "only", requestTemplate = "{}", priority = 50,
                state = TaskState.READY)
            repo.update { TaskMutation(it.copy(tasks = listOf(initial)), Unit) }
            assertFailsWith<IllegalArgumentException>(name) {
                repo.update<Int> {
                    throw IllegalArgumentException("rejected transaction")
                }
            }
            assertEquals(initial, repo.read { it.tasks.single() }, name)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(8)
            val winners = try {
                val jobs = (0 until 8).map {
                    pool.submit<Task?> {
                        start.await()
                        repo.update { current ->
                            val ready = current.tasks.single()
                            if (ready.state != TaskState.READY) {
                                TaskMutation(current, null)
                            } else {
                                val claimed = ready.copy(state = TaskState.RUNNING, attempt = 1,
                                    sessionId = "session-only-1")
                                TaskMutation(current.copy(tasks = listOf(claimed)), claimed)
                            }
                        }
                    }
                }
                start.countDown()
                jobs.map { it.get(5, TimeUnit.SECONDS) }.filterNotNull()
            } finally {
                pool.shutdownNow()
            }
            assertEquals(1, winners.size, name)
            repo.update { current ->
                val cancelled = current.tasks.single().copy(state = TaskState.CANCELLED)
                TaskMutation(current.copy(tasks = listOf(cancelled)), Unit)
            }
            // A stale finisher must not resurrect an already-cancelled attempt.
            repo.update { current ->
                val old = current.tasks.single()
                val next = if (old.state == TaskState.RUNNING &&
                    old.sessionId == "session-only-1") old.copy(state = TaskState.SUCCEEDED) else old
                TaskMutation(current.copy(tasks = listOf(next)), Unit)
            }
            name to repo.read { it.tasks.single().state to it.tasks.single().attempt }
        }
        assertEquals(results[0].second, results[1].second)
        assertEquals(TaskState.CANCELLED to 1, results[0].second)
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

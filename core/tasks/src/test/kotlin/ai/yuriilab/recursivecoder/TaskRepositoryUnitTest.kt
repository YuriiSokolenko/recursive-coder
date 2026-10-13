package ai.yuriilab.recursivecoder

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class TaskRepositoryUnitTest {
    @Test fun memoryAdapterRollsBackFailedMutation() {
        val repo = InMemoryTaskRepository()
        val initial = Task(id = "only", requestTemplate = "{}", priority = 50,
            state = TaskState.READY)
        repo.update { TaskMutation(it.copy(tasks = listOf(initial)), Unit) }
        assertFailsWith<IllegalArgumentException> {
            repo.update<Int> { current ->
                assertEquals(1, current.tasks.size)
                throw IllegalArgumentException("reject")
            }
        }
        assertEquals(listOf(initial), repo.read { it.tasks })
    }

    @Test fun policyClaimIsAtomicOnOneInMemoryRepository() {
        val repo = InMemoryTaskRepository()
        val initial = Task(id = "only", requestTemplate = "{}", priority = 50,
            state = TaskState.READY)
        repo.update { TaskMutation(it.copy(tasks = listOf(initial)), Unit) }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 8).map {
                pool.submit<Task?> {
                    start.await()
                    repo.update { queue ->
                        val decision = TaskPolicy.claim(queue, emptySet(), "2026-01-01T00:00:00Z")
                        TaskMutation(decision.queue, decision.task)
                    }
                }
            }
            start.countDown()
            assertEquals(1, futures.map { it.get(5, TimeUnit.SECONDS) }.filterNotNull().size)
            assertEquals(1, repo.read { it.tasks.single().attempt })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun schedulerExclusivityCannotBeReentered() {
        val repo = InMemoryTaskRepository()
        repo.withSchedulerLock {
            assertFailsWith<IllegalStateException> {
                repo.withSchedulerLock { }
            }
        }
        repo.withSchedulerLock { assertFalse(repo.read { it.paused }) }
    }
}

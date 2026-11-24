package ai.yuriilab.recursivecoder

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

@Tag("unit")
class EnqueueTaskUseCaseTest {
    private val fixed = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

    @Test fun interfacePerformsAtomicPolicyDrivenSubmission() {
        val repository = InMemoryTaskRepository()
        val useCase: EnqueueTaskUseCase = DefaultEnqueueTaskUseCase(repository, fixed)
        val first = useCase(taskRequestJsonForUnitTest("first"))
        val second = useCase(taskRequestJsonForUnitTest("second"), 80, listOf(first.id))

        assertEquals(TaskState.READY, first.state)
        assertEquals(TaskState.BLOCKED, second.state)
        assertEquals(80, second.priority)
        assertEquals("2026-01-01T00:00:00Z", first.transitions.first().occurredAt)
        assertEquals(listOf("first", "second"), repository.read { it.tasks.map(Task::id) })
    }

    @Test fun rejectionNeverPublishesPartialQueue() {
        val repository = InMemoryTaskRepository()
        val useCase: EnqueueTaskUseCase = DefaultEnqueueTaskUseCase(repository, fixed)
        assertFailsWith<IllegalArgumentException> { useCase(taskRequestJsonForUnitTest("x"), -1) }
        assertFailsWith<Refused> { useCase("{}") }
        assertTrue(repository.read { it.tasks }.isEmpty())

        useCase(taskRequestJsonForUnitTest("ok"))
        val before = repository.read { it }
        assertFailsWith<IllegalArgumentException> { useCase(taskRequestJsonForUnitTest("missing"), 50, listOf("absent")) }
        assertEquals(before, repository.read { it })
    }

    @Test fun competingSubmissionsOfSameIdYieldOneCommit() {
        val repository = InMemoryTaskRepository()
        val useCase: EnqueueTaskUseCase = DefaultEnqueueTaskUseCase(repository, fixed)
        val submission = taskRequestJsonForUnitTest("same")
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
        val service = TaskService(repository, VirtualTaskRunPaths, fixed, substitute)
        assertSame(expected, service.enqueue("intentionally not valid JSON"))
        assertTrue(repository.read { it.tasks }.isEmpty())
    }
}

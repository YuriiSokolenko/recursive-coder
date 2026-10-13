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

/** Exercise injected contracts directly: no TaskService, filesystem, Koin or model gateway. */
@Tag("unit")
class TaskOperationsUseCasesTest {
    private val fixed = Clock.fixed(Instant.parse("2026-02-03T04:05:06Z"), ZoneOffset.UTC)

    private fun task(
        id: String, state: TaskState = TaskState.READY, dependencies: List<String> = emptyList()
    ): Task = Task(id = id, requestTemplate = "{}", dependencies = dependencies, priority = 50, state = state)

    private fun repository(vararg tasks: Task): InMemoryTaskRepository =
        InMemoryTaskRepository().also { repository ->
            repository.update { TaskMutation(it.copy(tasks = tasks.toList()), Unit) }
        }

    @Test fun independentMutationsReconcileDependenciesAndPreserveRejectedWrites() {
        val repository = repository(task("parent"), task("child", TaskState.BLOCKED, listOf("parent")))
        val queries: TaskQueriesUseCase = DefaultTaskQueriesUseCase(repository)
        val cancel: CancelTaskUseCase = DefaultCancelTaskUseCase(repository, fixed)
        val retry: RetryTaskUseCase = DefaultRetryTaskUseCase(repository, fixed)
        val pause: PauseTaskQueueUseCase = DefaultPauseTaskQueueUseCase(repository, fixed)
        val resume: ResumeTaskQueueUseCase = DefaultResumeTaskQueueUseCase(repository, fixed)
        val replace: ReplaceTaskDependenciesUseCase = DefaultReplaceTaskDependenciesUseCase(repository, fixed)
        val cancelAll: CancelAllTasksUseCase = DefaultCancelAllTasksUseCase(repository, fixed)

        assertEquals(listOf("parent", "child"), queries.list().map(Task::id))
        assertEquals("2026-02-03T04:05:06Z", cancel("parent").transitions.last().occurredAt)
        assertEquals(TaskState.NEEDS_ATTENTION, queries.show("child").state)
        val beforeDenied = repository.read { it }
        assertFailsWith<IllegalArgumentException> { retry("child") }
        assertEquals(beforeDenied, repository.read { it }, "Denied retries must not commit")
        assertEquals(TaskState.READY, retry("parent").state)
        assertEquals(TaskState.BLOCKED, queries.show("child").state)

        pause()
        assertTrue(queries.isPaused())
        assertEquals(TaskState.PAUSED, queries.show("parent").state)
        resume()
        assertFalse(queries.isPaused())
        assertEquals(TaskState.READY, queries.show("parent").state)
        assertEquals(TaskState.READY, replace("child", emptyList()).state)
        assertFailsWith<IllegalArgumentException> { queries.show("missing") }
        val beforeCycle = repository.read { it }
        assertFailsWith<IllegalStateException> { replace("parent", listOf("parent")) }
        assertEquals(beforeCycle, repository.read { it }, "Invalid DAG must roll back")

        cancelAll()
        assertTrue(queries.isPaused())
        assertTrue(queries.list().all { it.state == TaskState.CANCELLED })
    }

    @Test fun claimsAreAtomicAndRespectActiveWorkerExclusions() {
        val repository = repository(task("only"))
        val claim: ClaimNextTaskUseCase = DefaultClaimNextTaskUseCase(repository, fixed)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val requests = (0 until 8).map {
                pool.submit<Task?> {
                    start.await()
                    claim(emptySet())
                }
            }
            start.countDown()
            val winners = requests.map { it.get(5, TimeUnit.SECONDS) }.filterNotNull()
            val first = winners.single()
            assertEquals("session-only-1", first.sessionId)
            assertEquals(1, first.attempt)
            assertEquals(TaskState.RUNNING, first.state)
            assertNull(claim(setOf("only")))
            assertEquals(first, repository.read { it.tasks.single() })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun staleCompletionCannotOverwriteCancelledOrNewerAttempt() {
        val repository = repository(task("only"))
        val claim: ClaimNextTaskUseCase = DefaultClaimNextTaskUseCase(repository, fixed)
        val complete: CompleteTaskAttemptUseCase = DefaultCompleteTaskAttemptUseCase(repository, fixed)
        val cancel: CancelTaskUseCase = DefaultCancelTaskUseCase(repository, fixed)
        val retry: RetryTaskUseCase = DefaultRetryTaskUseCase(repository, fixed)

        val first = assertNotNull(claim(emptySet()))
        val cancelled = cancel("only")
        assertEquals(cancelled, complete("only", first.sessionId!!, "succeeded"))
        retry("only")
        val second = assertNotNull(claim(emptySet()))
        assertEquals(2, second.attempt)
        assertEquals(second, complete("only", first.sessionId!!, "failed"))
        val beforeInvalid = repository.read { it }
        assertFailsWith<IllegalArgumentException> {
            complete("only", second.sessionId!!, "unknown-wire-status")
        }
        assertEquals(beforeInvalid, repository.read { it })
        assertEquals(TaskState.SUCCEEDED, complete("only", second.sessionId!!, "succeeded").state)
        assertEquals(SessionOutcome.SUCCEEDED, repository.read { it.tasks.single().lastResultStatus })
    }

    @Test fun interruptionAndRecoveryRequireNoUserCancellation() {
        val repository = repository(task("first"), task("second"))
        val claim: ClaimNextTaskUseCase = DefaultClaimNextTaskUseCase(repository, fixed)
        val interrupt: InterruptTaskAttemptUseCase = DefaultInterruptTaskAttemptUseCase(repository, fixed)
        val recover: RecoverInterruptedAttemptsUseCase = DefaultRecoverInterruptedAttemptsUseCase(repository, fixed)
        val queries: TaskQueriesUseCase = DefaultTaskQueriesUseCase(repository)

        repository.withSchedulerLock {
            val first = assertNotNull(claim(emptySet()))
            val second = assertNotNull(claim(emptySet()))
            assertEquals(TaskState.NEEDS_ATTENTION,
                interrupt(first.id, first.sessionId!!, "scheduler stopped").state)
            recover()
            assertEquals(TaskState.NEEDS_ATTENTION, queries.show(second.id).state)
            assertEquals("interrupted: reconcile before retry", queries.show(second.id).reason)
            val afterRecovery = repository.read { it }
            recover()
            assertEquals(afterRecovery, repository.read { it }, "Recovery must be idempotent")
            assertEquals(TaskState.NEEDS_ATTENTION, queries.show(first.id).state)
            assertEquals(TaskState.NEEDS_ATTENTION,
                interrupt(second.id, "stale-session", "should be ignored").state)
        }
    }

    @Test fun schedulerUsesInjectedInterfacesNotTaskServiceBusinessMethods() {
        val repository = repository(task("only"))
        val scheduler = TaskScheduler(
            repository = repository,
            queries = DefaultTaskQueriesUseCase(repository),
            claim = DefaultClaimNextTaskUseCase(repository, fixed),
            complete = DefaultCompleteTaskAttemptUseCase(repository, fixed),
            interrupt = DefaultInterruptTaskAttemptUseCase(repository, fixed),
            recover = DefaultRecoverInterruptedAttemptsUseCase(repository, fixed),
            prepare = PrepareTaskAttemptUseCase { throw IllegalStateException("projection refused") },
            runner = TaskRunner { _, _ -> error("Runner must not execute after projection failure") },
            pollMillis = 1
        )
        val report = scheduler.runUntilIdle()
        assertEquals(1, report.launched)
        assertEquals(1, report.completed)
        assertFalse(report.paused)
        assertEquals(TaskState.FAILED, DefaultTaskQueriesUseCase(repository).show("only").state)
    }
}

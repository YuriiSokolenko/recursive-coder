package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.test.verify.verify
import org.koin.dsl.module
import kotlin.test.*

/** Close-tracking in-memory port used to test resource lifetime. */
private class CloseTrackingTaskRepository(
    val events: MutableList<String> = mutableListOf(),
    private val onClose: () -> Unit = {}
) : TaskRepository by InMemoryTaskRepository(), AutoCloseable {
    var closeCount = 0
        private set
    var closeFailure: Throwable? = null

    override fun close() {
        onClose()
        closeCount++
        events += "repository"
        closeFailure?.let { throw it }
    }
}

private class KoinCloseProbe

/** All definitions are exercised with in-memory ports, no filesystem or global Koin. */
@Tag("unit")
class KoinBindingsTest {
    @Test fun actualCompositionClosesKoinBeforeOwnedRepositoryExactlyOnce() {
        val events = mutableListOf<String>()
        val repository = CloseTrackingTaskRepository(events) {
            assertEquals(
                listOf("koin"), events,
                "Koin's singleton onClose must fire before the repository closes"
            )
        }
        // This event can only be emitted by the real Koin container, not by
        // a callback manually called after TaskComposition.application.close().
        val closeProbe = module {
            single(createdAtStart = true) { KoinCloseProbe() } onClose {
                events += "koin"
            }
        }
        val composition = TaskComposition(
            repository,
            TaskRepositoryOwnership.OWNED,
            additionalModules = listOf(closeProbe)
        )
        assertSame(repository, composition.service().store)
        assertTrue(events.isEmpty())
        composition.close()
        composition.close()
        assertEquals(listOf("koin", "repository"), events)
        assertEquals(1, repository.closeCount)
        assertFailsWith<IllegalStateException> { composition.queries() }
    }

    @Test fun ownedCloseableRepositoryClosesOnlyOnce() {
        val repository = CloseTrackingTaskRepository()
        val composition = TaskComposition(repository, TaskRepositoryOwnership.OWNED)
        assertSame(repository, composition.service().store)
        composition.close()
        composition.close()
        assertEquals(1, repository.closeCount)
        assertFailsWith<IllegalStateException> { composition.queries() }
    }

    @Test fun borrowedCloseableRepositoryIsNeverClosedByComposition() {
        val repository = CloseTrackingTaskRepository()
        val composition = TaskComposition(repository, TaskRepositoryOwnership.BORROWED)
        assertSame(repository, composition.service().store)
        composition.close()
        composition.close()
        assertEquals(0, repository.closeCount)
        assertTrue(repository.read { it.tasks.isEmpty() })
    }

    @Test fun ownedRepositoryCloseFailureIsNotRetried() {
        val repository = CloseTrackingTaskRepository()
        val closeError = IllegalStateException("repository close failed")
        repository.closeFailure = closeError
        val composition = TaskComposition(repository, TaskRepositoryOwnership.OWNED)
        assertSame(closeError, assertFailsWith<IllegalStateException> { composition.close() })
        composition.close()
        assertEquals(1, repository.closeCount)
    }

    @Test fun dependencyCleanupRunsFirstAndBothFailuresAreRetained() {
        val events = mutableListOf<String>()
        val diFailure = IllegalStateException("DI close failed")
        val repositoryFailure = IllegalArgumentException("repository close failed")
        val caught = assertFailsWith<IllegalStateException> {
            closeTaskCompositionResources(
                closeApplication = { events += "koin"; throw diFailure },
                closeRepository = { events += "repository"; throw repositoryFailure }
            )
        }
        assertSame(diFailure, caught)
        assertEquals(listOf(repositoryFailure), caught.suppressed.toList())
        assertEquals(listOf("koin", "repository"), events)
    }

    @Test fun commandExceptionSurvivesRepositoryCleanupFailure() {
        val repository = CloseTrackingTaskRepository()
        val closeError = IllegalStateException("repository close failed")
        repository.closeFailure = closeError
        val commandError = IllegalArgumentException("command failed")
        val caught = assertFailsWith<IllegalArgumentException> {
            TaskComposition(repository, TaskRepositoryOwnership.OWNED).use {
                assertTrue(it.queries().list().isEmpty())
                throw commandError
            }
        }
        assertSame(commandError, caught)
        assertEquals(listOf(closeError), caught.suppressed.toList())
        assertEquals(1, repository.closeCount)
    }

    @Test fun repositoryClosesOnExceptionalCommandExit() {
        val repository = CloseTrackingTaskRepository()
        assertFailsWith<IllegalStateException> {
            TaskComposition(repository, TaskRepositoryOwnership.OWNED).use {
                assertTrue(it.queries().list().isEmpty())
                error("simulated failure")
            }
        }
        assertEquals(1, repository.closeCount)
    }

    @Test fun definitionsVerifyWithoutStartingGlobalKoin() {
        taskModule(InMemoryTaskRepository()).verify()
        sessionModule(FakeModel()).verify()
    }

    @Test fun oneTaskCompositionSharesRepositoryAndClosesAfterCommand() {
        val repository = InMemoryTaskRepository()
        val composition = TaskComposition(repository, TaskRepositoryOwnership.BORROWED)
        try {
            val first = composition.service()
            val second = composition.service()
            assertSame(repository, first.store)
            assertSame(first.store, second.store)
            assertNotSame(composition.enqueue(), composition.enqueue())
            assertTrue(composition.queries() is DefaultTaskQueriesUseCase)
            assertTrue(composition.cancel() is DefaultCancelTaskUseCase)
            assertTrue(composition.retry() is DefaultRetryTaskUseCase)
            assertTrue(composition.pause() is DefaultPauseTaskQueueUseCase)
            assertTrue(composition.resume() is DefaultResumeTaskQueueUseCase)
            assertTrue(composition.cancelAll() is DefaultCancelAllTasksUseCase)
            assertTrue(composition.replaceDependencies() is DefaultReplaceTaskDependenciesUseCase)
            assertTrue(composition.scheduler() is TaskScheduler)
            assertEquals(emptyList(), composition.queries().list())
            repository.update {
                TaskMutation(it.copy(tasks = listOf(
                    Task("koin", "{}", emptyList(), 50, state = TaskState.READY)
                )), Unit)
            }
            assertEquals(TaskState.CANCELLED, composition.cancel()("koin").state)
            assertEquals(TaskState.CANCELLED, first.show("koin").state)
        } finally {
            composition.close()
        }
        assertFailsWith<IllegalStateException> { composition.service() }
        assertFailsWith<IllegalStateException> { composition.enqueue() }
        assertFailsWith<IllegalStateException> { composition.queries() }
        assertFailsWith<IllegalStateException> { composition.scheduler() }
        composition.close() // Idempotent after command cleanup.
    }

    @Test fun sessionScopeResolvesFreshUseCasesAndClosesAfterExceptions() {
        val first = withRunSessionUseCase(FakeModel()) { it }
        val second = withRunSessionUseCase(FakeModel()) { it }
        assertNotSame(first, second)
        assertTrue(first is DefaultRunSessionUseCase)
        assertFailsWith<IllegalStateException> {
            withRunSessionUseCase(FakeModel()) {
                throw IllegalStateException("simulated execution failure")
            }
        }
        // A failed invocation cannot pollute subsequent dependency resolution.
        withRunSessionUseCase(FakeModel()) { assertTrue(it is DefaultRunSessionUseCase) }
    }
}

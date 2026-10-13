package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.test.verify.verify
import kotlin.test.*

/** All definitions are exercised with in-memory ports, no filesystem or global Koin. */
@Tag("unit")
class KoinBindingsTest {
    @Test fun definitionsVerifyWithoutStartingGlobalKoin() {
        taskModule(InMemoryTaskRepository()).verify()
        sessionModule(FakeModel()).verify()
    }

    @Test fun oneTaskCompositionSharesRepositoryAndClosesAfterCommand() {
        val repository = InMemoryTaskRepository()
        val composition = TaskComposition(repository)
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

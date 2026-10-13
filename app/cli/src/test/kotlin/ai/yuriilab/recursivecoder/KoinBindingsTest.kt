package ai.yuriilab.recursivecoder

import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.dsl.koinApplication
import org.koin.test.verify.verify
import kotlin.test.*

@Tag("integration")
class KoinBindingsTest {
    @TempDir lateinit var temp: Path

    @Test fun definitionsVerifyWithoutStartingGlobalKoin() {
        taskModule(temp.resolve("task-store")).verify()
        sessionModule(FakeModel()).verify()
    }

    @Test fun contractsResolveWithScopedResourcesAndNoGlobalContainer() {
        val tasks = koinApplication { modules(taskModule(temp.resolve("tasks"))) }
        val sessions = koinApplication { modules(sessionModule(FakeModel()) ) }
        try {
            assertNotSame(tasks.koin.get<EnqueueTaskUseCase>(), tasks.koin.get<EnqueueTaskUseCase>())
            val service = tasks.koin.get<TaskService>()
            assertSame(tasks.koin.get<TaskRepository>(), service.store)
            assertNotSame(sessions.koin.get<RunSessionUseCase>(), sessions.koin.get<RunSessionUseCase>())
            assertTrue(sessions.koin.get<RunSessionUseCase>() is DefaultRunSessionUseCase)
        } finally {
            tasks.close()
            sessions.close()
        }
    }
}

package ai.yuriilab.recursivecoder

import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/** No disk, model or CLI is required to verify Task protocol boundary failures. */
@Tag("unit")
class TaskRequestMapperUnitTest {
    @Test fun invalidInboundEnvelopesAreRejectedBeforeCreatingTasks() {
        for (raw in listOf("", "{", "{}", "[]", "null",
                """{"kind":"session-request","version":"1.0"}""")) {
            assertFailsWith<Refused> { TaskRequestMapper.submission(raw) }
        }
        assertFailsWith<IllegalArgumentException> {
            TaskRequestMapper.submission("x".repeat(262145))
        }
    }

    @Test fun invalidStoredTemplateAndTaskStateCannotCreateSchedulerRequest() {
        val running = Task(id = "task", requestTemplate = "{}",
            dependencies = emptyList(), priority = 50,
            state = TaskState.RUNNING, attempt = 1, sessionId = "session-task-1")
        val output = Path.of("/virtual/task/attempt-1")
        assertFailsWith<Refused> { TaskRequestMapper.forAttempt(running, output) }
        assertFailsWith<IllegalArgumentException> {
            TaskRequestMapper.forAttempt(running.copy(state = TaskState.READY), output)
        }
    }

    @Test fun unrecognizedRunnerResultFailsBeforeAnyDurableMutation() {
        val service = TaskService(InMemoryTaskRepository())
        val failure = assertFailsWith<IllegalArgumentException> {
            service.finish("unknown", "session-unknown-1", "secret-user-text")
        }
        assertEquals("Unsupported session result status", failure.message)
        assertTrue(service.list().isEmpty())
    }
}

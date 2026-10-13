package ai.yuriilab.recursivecoder

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
}

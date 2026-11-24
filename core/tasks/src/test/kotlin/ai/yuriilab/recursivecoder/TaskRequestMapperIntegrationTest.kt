package ai.yuriilab.recursivecoder

import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Path-based invocation mapping belongs to the integration tier even with virtual paths. */
@Tag("integration")
class TaskRequestMapperIntegrationTest {
    @Test fun claimedAttemptReplacesInvocationWithStableSchedulerWireValues() {
        val template = taskRequestJsonForUnitTest("mapped")
        val original = SessionRequest.parse(template)
        val originalJson = original.raw.toString()
        assertEquals("cli", original.invocation.textAt("source"))
        assertEquals(1L, original.invocation.longAt("attempt"))

        for (attempt in listOf(1, 2, Int.MAX_VALUE)) {
            val task = Task("mapped", template, emptyList(), 50,
                state = TaskState.RUNNING, attempt = attempt, sessionId = "session-mapped-$attempt")
            val mapped = TaskRequestMapper.forAttempt(task, Path.of("/virtual/run-$attempt"))
            val roundTrip = SessionRequest.parse(mapped.raw.toString())
            assertEquals("scheduler", roundTrip.invocation.textAt("source"))
            assertEquals(attempt.toLong(), roundTrip.invocation.longAt("attempt"))
            assertEquals("invoke-mapped-$attempt", roundTrip.invocation.textAt("invocationId"))
            assertEquals(original.task, roundTrip.task)
        }
        assertEquals(originalJson, original.raw.toString())
        assertEquals("cli", original.invocation.textAt("source"))
        assertEquals(1L, original.invocation.longAt("attempt"))
    }

    @Test fun nonpositiveClaimedAttemptsFailWithInvalidAttemptDiagnostic() {
        val template = taskRequestJsonForUnitTest("mapped")
        for (attempt in listOf(0, -1, Int.MIN_VALUE)) {
            val invalid = Task("mapped", template, emptyList(), 50,
                state = TaskState.RUNNING, attempt = attempt, sessionId = "session-mapped-$attempt")
            val failure = assertFailsWith<Refused> {
                TaskRequestMapper.forAttempt(invalid, Path.of("/virtual/run-$attempt"))
            }
            assertEquals("INVALID_SESSION_REQUEST", failure.code)
            assertEquals("validation", failure.category)
            assertEquals("Invalid attempt", failure.explanation)
            assertEquals("Invalid attempt", failure.message)
        }
    }
}

package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Tag("integration")
class TaskSnapshotVocabularyIntegrationTest {
    @TempDir lateinit var temp: Path

    // Independent v1 oracle: neither writer nor reader constants define these bytes.
    private val snapshot = """{"version":1,"paused":false,"nextOrder":1,"dispatches":1,"tasks":[{"id":"job","requestTemplate":"{}","dependencies":[],"priority":50,"state":"failed","attempt":1,"sessionId":"session-job-1","lastResultStatus":"timed_out","reason":"timeout","queueOrder":1,"waitTurns":0,"preserveOrderOnResume":false,"prunedTransitions":0,"transitions":[{"from":null,"to":"created","at":"at","attempt":0,"sessionId":null,"reason":"enqueued"},{"from":"running","to":"failed","at":"later","attempt":1,"sessionId":"session-job-1","reason":"timeout"}]}]}"""

    private fun expectedTask() = Task(
        "job", "{}", emptyList(), 50, state = TaskState.FAILED, attempt = 1,
        sessionId = "session-job-1", lastResultStatus = SessionOutcome.TIMED_OUT,
        reason = "timeout", queueOrder = 1,
        transitions = listOf(
            TaskTransition(null, TaskState.CREATED, "at", 0, null, "enqueued"),
            TaskTransition(TaskState.RUNNING, TaskState.FAILED, "later", 1, "session-job-1", "timeout")
        )
    )

    @Test fun writerAndReaderMatchIndependentV1Snapshot() {
        val expected = TaskQueue(nextOrder = 1, dispatches = 1, tasks = listOf(expectedTask()))
        val writeDirectory = temp.resolve("write")
        TaskStore(writeDirectory).update { TaskMutation(expected, Unit) }
        assertEquals(snapshot, Files.readString(writeDirectory.resolve("tasks.json")))
        assertEquals(expected, TaskStore(writeDirectory).read { it })

        val readDirectory = temp.resolve("read")
        val reader = TaskStore(readDirectory)
        Files.writeString(readDirectory.resolve("tasks.json"), snapshot)
        assertEquals(expected, reader.read { it })
    }

    @Test fun allTaskStatesAndSessionOutcomesRoundTripThroughStore() {
        val store = TaskStore(temp)
        // Deliberately synthetic combinations and self-transitions exercise only the
        // persistence codec, not TaskPolicy's reachable lifecycle or transition rules.
        for (state in TaskState.entries) {
            for (outcome in SessionOutcome.entries) {
                val task = expectedTask().copy(state = state, lastResultStatus = outcome,
                    transitions = listOf(TaskTransition(state, state, "at", 1, "session-job-1", null)))
                val queue = TaskQueue(nextOrder = 1, dispatches = 1, tasks = listOf(task))
                store.update { TaskMutation(queue, Unit) }
                assertEquals(queue, TaskStore(temp).read { it }, "$state / $outcome")
            }
        }
    }

    @Test fun unknownStateOrOutcomeRejectsReadAndWriteWithoutClobbering() {
        val store = TaskStore(temp)
        val path = temp.resolve("tasks.json")
        val invalidFields = mapOf(
            "\"state\":\"failed\"" to "Unknown task state",
            "\"from\":\"running\"" to "Unknown task state",
            "\"to\":\"failed\"" to "Unknown task state",
            "\"lastResultStatus\":\"timed_out\"" to "Unsupported session result status"
        )
        for ((field, expectedCause) in invalidFields) {
            val poisoned = snapshot.replace(field, field.substringBefore(':') + ":\"unknown\"")
            assertNotEquals(snapshot, poisoned)
            Files.writeString(path, poisoned)
            val readFailure = assertFailsWith<IllegalStateException> { store.read { it } }
            assertEquals("Corrupt task store; refusing to overwrite it", readFailure.message)
            assertEquals(expectedCause, readFailure.cause?.message, field)
            assertFailsWith<IllegalStateException> {
                store.update<Unit> { fail("Corrupt snapshot must not reach mutation callback") }
            }
            assertEquals(poisoned, Files.readString(path))
        }
    }
}

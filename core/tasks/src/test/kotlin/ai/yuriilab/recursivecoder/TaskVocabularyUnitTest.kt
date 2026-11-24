package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

@Tag("unit")
class TaskVocabularyUnitTest {
    @Test fun priorityBoundsAndTaskFieldsRemainStable() {
        assertEquals(listOf(0, 50, 100), listOf(TaskPriority.MIN, TaskPriority.DEFAULT, TaskPriority.MAX))
        assertEquals(listOf("state", "priority", "dependencies", "attempt", "prunedTransitions", "sessionId", "lastResultStatus", "reason", "transitions"),
            listOf(TaskWireFields.STATE, TaskWireFields.PRIORITY, TaskWireFields.DEPENDENCIES, TaskWireFields.ATTEMPT, TaskWireFields.PRUNED_TRANSITIONS, TaskWireFields.SESSION_ID, TaskWireFields.LAST_RESULT_STATUS, TaskWireFields.REASON, TaskWireFields.TRANSITIONS))
        assertEquals(listOf("from", "to", "at"),
            listOf(TaskTransitionWireFields.FROM, TaskTransitionWireFields.TO, TaskTransitionWireFields.AT))
    }

    @Test fun taskStateCodecRetainsIndependentWireOracle() {
        val states = mapOf("created" to TaskState.CREATED, "ready" to TaskState.READY,
            "blocked" to TaskState.BLOCKED, "running" to TaskState.RUNNING,
            "paused" to TaskState.PAUSED, "succeeded" to TaskState.SUCCEEDED,
            "failed" to TaskState.FAILED, "needs-attention" to TaskState.NEEDS_ATTENTION,
            "cancelled" to TaskState.CANCELLED)
        assertEquals(states.keys, TaskState.entries.map { it.wire }.toSet())
        for ((wire, state) in states) {
            assertEquals(wire, state.wire)
            assertEquals(state, TaskState.fromWire(wire))
        }
        for (invalid in listOf("", "READY", "timed_out", "unknown")) {
            assertFailsWith<IllegalStateException> { TaskState.fromWire(invalid) }
        }
    }
}

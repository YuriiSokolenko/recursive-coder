package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Gap cases from issue #37. Only Task value snapshots and explicit logical time are used.
 * Persistent TaskStore and scheduler concurrency belong to integration tests.
 */
@Tag("unit")
class TaskPolicyCoverageUnitTest {
    private val at = "2026-11-24T08:00:00Z"

    private fun task(
        id: String,
        state: TaskState = TaskState.READY,
        priority: Int = 50,
        order: Long = 1,
        dependencies: List<String> = emptyList(),
        attempt: Int = 0,
        sessionId: String? = null,
        reason: String? = null
    ) = Task(
        id = id, requestTemplate = "{}", dependencies = dependencies, priority = priority,
        state = state, queueOrder = order, attempt = attempt, sessionId = sessionId, reason = reason
    )

    @Test fun transitionMatrixCoversEveryDeclaredEdgeAndEveryDeniedEdge() {
        val edges = mapOf(
            TaskState.CREATED to setOf(TaskState.READY, TaskState.BLOCKED, TaskState.PAUSED,
                TaskState.NEEDS_ATTENTION, TaskState.CANCELLED),
            TaskState.READY to setOf(TaskState.RUNNING, TaskState.BLOCKED, TaskState.PAUSED,
                TaskState.NEEDS_ATTENTION, TaskState.CANCELLED),
            TaskState.BLOCKED to setOf(TaskState.READY, TaskState.PAUSED,
                TaskState.NEEDS_ATTENTION, TaskState.CANCELLED),
            TaskState.PAUSED to setOf(TaskState.READY, TaskState.BLOCKED,
                TaskState.NEEDS_ATTENTION, TaskState.CANCELLED),
            TaskState.RUNNING to setOf(TaskState.SUCCEEDED, TaskState.FAILED,
                TaskState.CANCELLED, TaskState.NEEDS_ATTENTION),
            TaskState.SUCCEEDED to emptySet(),
            TaskState.FAILED to setOf(TaskState.BLOCKED),
            TaskState.CANCELLED to setOf(TaskState.BLOCKED),
            TaskState.NEEDS_ATTENTION to setOf(TaskState.READY, TaskState.BLOCKED,
                TaskState.PAUSED, TaskState.CANCELLED)
        )
        assertEquals(TaskState.entries.toSet(), edges.keys, "Every Task state must be audited")
        for (from in TaskState.entries) {
            for (to in TaskState.entries) {
                val before = task("job", state = from, reason = "old")
                val description = "${from.wire} -> ${to.wire}"
                if (from == to || to in edges.getValue(from)) {
                    val decision = TaskPolicy.transition(before, to, "new", 12, at)
                    assertEquals(to, decision.task.state, description)
                    assertEquals("new", decision.task.reason, description)
                    assertEquals(1, decision.task.transitions.size, description)
                    assertEquals(from, decision.task.transitions.single().from, description)
                    assertEquals(at, decision.task.transitions.single().occurredAt, description)
                    val requeued = to == TaskState.READY && from != TaskState.READY
                    assertEquals(if (requeued) 13L else 12L, decision.nextOrder, description)
                } else {
                    assertFailsWith<IllegalArgumentException>(description) {
                        TaskPolicy.transition(before, to, "new", 12, at)
                    }
                }
                assertEquals("old", before.reason, "Input mutated during $description")
                assertTrue(before.transitions.isEmpty(), "Input history mutated during $description")
            }
        }
    }

    @Test fun dependencyReadinessAndFailurePropagationCoverPrerequisiteStates() {
        data class Case(val prerequisite: TaskState, val child: TaskState, val reason: String?)
        val cases = listOf(
            Case(TaskState.SUCCEEDED, TaskState.READY, null),
            Case(TaskState.READY, TaskState.BLOCKED, null),
            Case(TaskState.RUNNING, TaskState.BLOCKED, null),
            Case(TaskState.FAILED, TaskState.NEEDS_ATTENTION, "dependency:parent:failed"),
            Case(TaskState.CANCELLED, TaskState.NEEDS_ATTENTION, "dependency:parent:cancelled"),
            Case(TaskState.NEEDS_ATTENTION, TaskState.NEEDS_ATTENTION,
                "dependency:parent:needs-attention")
        )
        for (case in cases) {
            // Reverse input order proves that the policy uses graph order, not list order.
            val queue = TaskQueue(nextOrder = 3, tasks = listOf(
                task("grandchild", state = TaskState.BLOCKED, order = 3,
                    dependencies = listOf("child")),
                task("child", state = TaskState.BLOCKED, order = 2,
                    dependencies = listOf("parent")),
                task("parent", state = case.prerequisite, order = 1,
                    reason = if (case.prerequisite == TaskState.NEEDS_ATTENTION)
                        "manual review" else null)
            ))
            val actual = TaskPolicy.reconcile(queue, at)
            val child = actual.tasks[1]
            val grandchild = actual.tasks[0]
            assertEquals(case.child, child.state, "parent=${case.prerequisite}")
            assertEquals(case.reason, child.reason, "parent=${case.prerequisite}")
            val propagated = case.child == TaskState.NEEDS_ATTENTION
            assertEquals(if (propagated) TaskState.NEEDS_ATTENTION else TaskState.BLOCKED,
                grandchild.state, "grandchild after parent=${case.prerequisite}")
            assertEquals(if (propagated) "dependency:child:needs-attention" else null,
                grandchild.reason, "grandchild reason after parent=${case.prerequisite}")
            assertEquals(TaskState.BLOCKED, queue.tasks[1].state,
                "Reconciliation must not change the input snapshot")
        }
    }

    @Test fun eighthDispatchOldestSelectionAndExclusionsAreDeterministic() {
        for (count in listOf(0L, 6L, 7L, 8L, 14L, 15L, 16L)) {
            val queue = TaskQueue(nextOrder = 2, dispatches = count, tasks = listOf(
                task("oldest", priority = 0, order = 1),
                task("highest", priority = 100, order = 2)
            ))
            val claim = TaskPolicy.claim(queue, emptySet(), at)
            val expected = if (count % 8L == 7L) "oldest" else "highest"
            assertEquals(expected, claim.task?.id, "dispatches=$count")
            assertEquals(count + 1L, claim.queue.dispatches, "dispatches=$count")
            assertEquals(1L, claim.queue.tasks.single { it.id != expected }.waitTurns,
                "Loser must earn exactly one wait turn for dispatches=$count")
            val excluded = TaskPolicy.claim(queue, setOf("oldest"), at)
            assertEquals("highest", excluded.task?.id,
                "Excluded oldest task cannot override the candidate set at dispatches=$count")
            val none = TaskPolicy.claim(queue, setOf("oldest", "highest"), at)
            assertNull(none.task, "All tasks excluded at dispatches=$count")
            assertEquals(count, none.queue.dispatches, "No eligible dispatch must not advance count")
        }
    }

    @Test fun cancelAllAndManualRetryFenceStaleCompletionAndKeepQueuePaused() {
        val original = TaskQueue(nextOrder = 7, tasks = listOf(
            task("ready"),
            task("blocked", state = TaskState.BLOCKED),
            task("running", state = TaskState.RUNNING, attempt = 1,
                sessionId = "session-running-1"),
            task("attention", state = TaskState.NEEDS_ATTENTION, reason = "manual review"),
            task("success", state = TaskState.SUCCEEDED),
            task("failure", state = TaskState.FAILED),
            task("cancelled", state = TaskState.CANCELLED)
        ))
        val stopped = TaskPolicy.cancelAll(original, at)
        assertTrue(stopped.paused, "Global cancellation must pause the queue")
        for (id in listOf("ready", "blocked", "running", "attention")) {
            assertEquals(TaskState.CANCELLED, stopped.tasks.single { it.id == id }.state,
                "Global cancellation of $id")
        }
        for ((id, expected) in listOf(
            "success" to TaskState.SUCCEEDED,
            "failure" to TaskState.FAILED,
            "cancelled" to TaskState.CANCELLED
        )) {
            assertEquals(expected, stopped.tasks.single { it.id == id }.state,
                "Existing terminal disposition of $id must survive cancelAll")
        }
        assertEquals(TaskState.RUNNING, original.tasks[2].state, "Original snapshot was mutated")
        assertEquals(stopped, TaskPolicy.finish(stopped, "running",
            "session-running-1", SessionOutcome.SUCCEEDED, at),
            "Late successful completion must not override cancellation")

        val retry = TaskPolicy.retry(stopped, "running", at)
        val pending = retry.tasks.single { it.id == "running" }
        assertTrue(retry.paused, "Retry must not unpause the globally stopped queue")
        assertEquals(TaskState.PAUSED, pending.state)
        assertNull(pending.sessionId, "Retry must clear the previous session owner")
        assertEquals(1, pending.attempt, "Retry does not increment attempt before claim")
        val resumed = TaskPolicy.resume(retry, at)
        val claim = TaskPolicy.claim(resumed, emptySet(), at)
        // Other retried-independent tasks may also be ready: constrain the candidate set.
        val runningClaim = TaskPolicy.claim(resumed,
            resumed.tasks.map { it.id }.filter { it != "running" }.toSet(), at)
        assertNotNull(claim.task, "Resume must offer at least one runnable task")
        assertEquals("running", runningClaim.task?.id)
        assertEquals("session-running-2", runningClaim.task?.sessionId)
        assertEquals(runningClaim.queue, TaskPolicy.finish(runningClaim.queue, "running",
            "session-running-1", SessionOutcome.SUCCEEDED, at),
            "Previous owner must be fenced after retry")
    }

    @Test fun storedScalarInvariantsRejectInvalidSnapshotsWithoutAdapters() {
        val good = task("valid", order = 1)
        val invalid = listOf(
            "unsafe-id" to good.copy(id = "../escape"),
            "low-priority" to good.copy(priority = -1),
            "high-priority" to good.copy(priority = 101),
            "negative-attempt" to good.copy(attempt = -1),
            "negative-order" to good.copy(queueOrder = -1),
            "negative-wait" to good.copy(waitTurns = -1),
            "negative-pruned-history" to good.copy(prunedTransitions = -1)
        )
        validateStoredTasks(listOf(good))
        for ((name, snapshot) in invalid) {
            assertFailsWith<IllegalArgumentException>("Invalid stored task invariant: $name") {
                validateStoredTasks(listOf(snapshot))
            }
        }
        assertEquals(task("valid", order = 1), good, "Invalid candidates cannot mutate the baseline")
    }

    @Test fun dependencyEditsArePermittedOnlyBeforeFirstAttempt() {
        val queue = TaskQueue(nextOrder = 2, tasks = listOf(
            task("child", state = TaskState.BLOCKED, order = 2,
                dependencies = listOf("parent")),
            task("parent", state = TaskState.READY, order = 1)
        ))
        val detached = TaskPolicy.replaceDependencies(queue, "child", emptyList(), at)
        assertEquals(TaskState.READY, detached.tasks.first().state,
            "Removing an unsatisfied dependency must make the task ready immediately")
        assertTrue(queue.tasks.first().dependencies.isNotEmpty(),
            "Dependency edit must return a new snapshot")
        val executed = detached.copy(tasks = detached.tasks.map {
            if (it.id == "child") it.copy(attempt = 1) else it
        })
        assertFailsWith<IllegalArgumentException>("Dependency edits after execution are forbidden") {
            TaskPolicy.replaceDependencies(executed, "child", listOf("parent"), at)
        }
    }
}

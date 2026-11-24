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
        }
    }

    @Test fun firstFailedPrerequisiteFollowsDeclaredDependencyOrder() {
        val failed = task("failed", state = TaskState.FAILED)
        val cancelled = task("cancelled", state = TaskState.CANCELLED)
        for ((dependencies, expected) in listOf(
            listOf("failed", "cancelled") to "dependency:failed:failed",
            listOf("cancelled", "failed") to "dependency:cancelled:cancelled"
        )) {
            val child = task("child", state = TaskState.BLOCKED, dependencies = dependencies)
            val queue = TaskQueue(tasks = listOf(child, cancelled, failed))
            val actual = TaskPolicy.reconcile(queue, at).tasks.single { it.id == "child" }
            assertEquals(TaskState.NEEDS_ATTENTION, actual.state, "dependencies=$dependencies")
            assertEquals(expected, actual.reason, "First failed prerequisite for $dependencies")
        }
    }

    @Test fun eighthDispatchOldestSelectionAndExclusionsAreDeterministic() {
        val cases = listOf(0L to "highest", 6L to "highest", 7L to "oldest",
            8L to "highest", 14L to "highest", 15L to "oldest", 16L to "highest")
        for ((count, expected) in cases) {
            val queue = TaskQueue(nextOrder = 2, dispatches = count, tasks = listOf(
                task("oldest", priority = 0, order = 1),
                task("highest", priority = 100, order = 2)
            ))
            val claim = TaskPolicy.claim(queue, emptySet(), at)
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
        assertEquals(stopped, TaskPolicy.finish(stopped, "running",
            "session-running-1", SessionOutcome.SUCCEEDED, at),
            "Late successful completion must not override cancellation")

        val retry = TaskPolicy.retry(stopped, "running", at)
        val pending = retry.tasks.single { it.id == "running" }
        assertTrue(retry.paused, "Retry must not unpause the globally stopped queue")
        assertEquals(TaskState.PAUSED, pending.state, "Retried task must remain paused")
        assertNull(pending.sessionId, "Retry must clear the previous session owner")
        assertEquals(1, pending.attempt, "Retry does not increment attempt before claim")
        val resumed = TaskPolicy.resume(retry, at)
        val claim = TaskPolicy.claim(resumed, emptySet(), at)
        assertEquals("running", claim.task?.id, "Only the retried task may run")
        assertEquals("session-running-2", claim.task?.sessionId, "Claim must issue a new owner")
        val stale = TaskPolicy.finish(claim.queue, "running",
            "session-running-1", SessionOutcome.SUCCEEDED, at)
        assertEquals(claim.queue, stale, "Previous owner must be fenced after retry")
        val completed = TaskPolicy.finish(stale, "running",
            "session-running-2", SessionOutcome.SUCCEEDED, at)
        val result = completed.tasks.single { it.id == "running" }
        assertEquals(TaskState.SUCCEEDED, result.state, "Current owner must be able to finish")
        assertEquals(SessionOutcome.SUCCEEDED, result.lastResultStatus, "Record current owner outcome")
    }

    @Test fun storedScalarInvariantsRejectInvalidSnapshotsWithoutAdapters() {
        val good = task("valid", order = 1)
        val invalid = listOf(
            "unsafe-id" to good.copy(id = "../escape"),
            "65-character-id" to good.copy(id = "a".repeat(65)),
            "leading-hyphen" to good.copy(id = "-valid"),
            "leading-underscore" to good.copy(id = "_valid"),
            "low-priority" to good.copy(priority = -1),
            "high-priority" to good.copy(priority = 101),
            "negative-attempt" to good.copy(attempt = -1),
            "negative-order" to good.copy(queueOrder = -1),
            "negative-wait" to good.copy(waitTurns = -1),
            "negative-pruned-history" to good.copy(prunedTransitions = -1)
        )
        validateStoredTasks(listOf(good.copy(priority = 0)))
        validateStoredTasks(listOf(good.copy(priority = 100)))
        validateStoredTasks(listOf(good.copy(id = "a".repeat(64))))
        validateStoredTasks(listOf(good.copy(id = "a")))
        for ((name, snapshot) in invalid) {
            assertFailsWith<IllegalArgumentException>("Invalid stored task invariant: $name") {
                validateStoredTasks(listOf(snapshot))
            }
        }
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

    @Test fun lastRepresentableAttemptIsClaimableButNeverRetriableAfterCompletion() {
        val previous = task("last", state = TaskState.FAILED, attempt = Int.MAX_VALUE - 1,
            sessionId = "session-last-${Int.MAX_VALUE - 1}")
        val queue = TaskQueue(nextOrder = 1, tasks = listOf(previous))
        val retry = TaskPolicy.retry(queue, previous.id, at)
        assertNull(retry.tasks.single().sessionId)
        val claim = TaskPolicy.claim(retry, emptySet(), at)
        val running = assertNotNull(claim.task)
        assertEquals(Int.MAX_VALUE, running.attempt)
        assertEquals("session-last-${Int.MAX_VALUE}", running.sessionId)
        assertEquals(Int.MAX_VALUE, running.transitions.last().attempt)
        validateStoredTasks(claim.queue.tasks)
        assertEquals(claim.queue, TaskPolicy.finish(claim.queue, running.id,
            previous.sessionId!!, SessionOutcome.SUCCEEDED, at))
        for (outcome in SessionOutcome.entries) {
            val finished = TaskPolicy.finish(claim.queue, running.id, running.sessionId!!, outcome, at)
            val terminal = finished.tasks.single()
            assertEquals(Int.MAX_VALUE, terminal.attempt)
            assertEquals(outcome, terminal.lastResultStatus)
            assertEquals(running.sessionId, terminal.sessionId)
            val expectedReason = if (outcome == SessionOutcome.SUCCEEDED)
                "Only terminal/attention tasks can be retried" else "attempt counter exhausted"
            val rejection = assertFailsWith<IllegalArgumentException> {
                TaskPolicy.retry(finished, running.id, at)
            }
            assertEquals(expectedReason, rejection.message, outcome.wire)
            assertNull(TaskPolicy.claim(finished, emptySet(), at).task)
        }
    }

    @Test fun exhaustedLegacyDependencyAttentionRejectsRetryBeforePrerequisiteCheck() {
        val parent = task("parent", state = TaskState.FAILED)
        val child = task("child", state = TaskState.NEEDS_ATTENTION, order = 2,
            dependencies = listOf(parent.id), attempt = Int.MAX_VALUE,
            reason = "dependency:parent:failed")
        val queue = TaskQueue(nextOrder = 2, tasks = listOf(child, parent))
        validateStoredTasks(queue.tasks)

        // Exercise retry directly on a legacy snapshot, before reconciliation.
        assertEquals("attempt counter exhausted", assertFailsWith<IllegalArgumentException> {
            TaskPolicy.retry(queue, child.id, at)
        }.message)
        val belowLimit = queue.copy(tasks = listOf(child.copy(attempt = Int.MAX_VALUE - 1), parent))
        assertEquals("Retry the failed prerequisite first; this task is blocked by a dependency",
            assertFailsWith<IllegalArgumentException> {
                TaskPolicy.retry(belowLimit, child.id, at)
            }.message)
    }

    @Test fun unattemptedDependentCanBeRepointedToReplacementForExhaustedTask() {
        val exhausted = task("old", attempt = Int.MAX_VALUE)
        val child = task("child", state = TaskState.BLOCKED, order = 2,
            dependencies = listOf(exhausted.id))
        val queue = TaskPolicy.reconcile(TaskQueue(nextOrder = 2, tasks = listOf(child, exhausted)), at)
        assertEquals("dependency:old:needs-attention", queue.tasks.first().reason)

        val replacement = TaskPolicy.enqueue(queue, "replacement", "{}", 50, emptyList(), at)
        val repointed = TaskPolicy.replaceDependencies(replacement, child.id, listOf("replacement"), at)
        assertEquals(TaskState.BLOCKED, repointed.tasks.first().state)
        assertEquals(listOf("replacement"), repointed.tasks.first().dependencies)
        val claim = TaskPolicy.claim(repointed, emptySet(), at)
        val running = assertNotNull(claim.task)
        assertEquals("replacement", running.id)
        val finished = TaskPolicy.finish(claim.queue, running.id, running.sessionId!!,
            SessionOutcome.SUCCEEDED, at)
        assertEquals(child.id, TaskPolicy.claim(finished, emptySet(), at).task?.id)
        assertEquals(queue.tasks[1], finished.tasks[1])
    }

    @Test fun exhaustedQueuedTasksAreQuarantinedWithoutConsumingDispatchOrBlockingOthers() {
        for (state in listOf(TaskState.CREATED, TaskState.READY, TaskState.BLOCKED,
                TaskState.PAUSED, TaskState.NEEDS_ATTENTION)) {
            for (dispatches in listOf(0L, 7L)) {
                val exhausted = task("exhausted", state = state, priority = 100,
                    attempt = Int.MAX_VALUE, reason = if (state == TaskState.NEEDS_ATTENTION)
                        "dependency:parent:failed" else null)
                val child = task("child", state = TaskState.BLOCKED, order = 2,
                    dependencies = listOf(exhausted.id))
                val other = task("other", priority = 0, order = 3)
                val queue = TaskQueue(nextOrder = 3, dispatches = dispatches,
                    tasks = listOf(child, exhausted, other))
                validateStoredTasks(queue.tasks)
                val claim = TaskPolicy.claim(queue, emptySet(), at)
                assertEquals(other.id, claim.task?.id, "$state / $dispatches")
                assertEquals(dispatches + 1, claim.queue.dispatches)
                val quarantined = claim.queue.tasks[1]
                assertEquals(TaskState.NEEDS_ATTENTION, quarantined.state)
                assertEquals("attempt counter exhausted", quarantined.reason)
                assertEquals(Int.MAX_VALUE, quarantined.attempt)
                assertNull(quarantined.sessionId)
                assertEquals(Int.MAX_VALUE, quarantined.transitions.single().attempt)
                assertEquals("dependency:exhausted:needs-attention", claim.queue.tasks[0].reason)
                assertEquals(claim.queue, TaskPolicy.reconcile(claim.queue, at))
                val rejected = assertFailsWith<IllegalArgumentException> {
                    TaskPolicy.retry(claim.queue, exhausted.id, at)
                }
                assertEquals("attempt counter exhausted", rejected.message)
                val idle = TaskPolicy.claim(claim.queue, emptySet(), at)
                assertNull(idle.task)
                assertEquals(claim.queue, idle.queue)

                // Even a paused queue or an excluded exhausted task is reconciled.
                val paused = TaskPolicy.claim(queue.copy(paused = true), setOf(exhausted.id), at)
                assertNull(paused.task)
                assertEquals(dispatches, paused.queue.dispatches)
                assertEquals(quarantined, paused.queue.tasks[1])
                assertEquals(TaskState.PAUSED, paused.queue.tasks[2].state)
                assertEquals(other.id, TaskPolicy.claim(
                    TaskPolicy.resume(paused.queue, at), emptySet(), at).task?.id)
            }
        }
    }

    @Test fun recoveryPreservesFinalAttemptOwnerAndRefusesRetryWithoutReplaying() {
        val running = task("last", state = TaskState.RUNNING, attempt = Int.MAX_VALUE,
            sessionId = "session-last-${Int.MAX_VALUE}")
        val queue = TaskQueue(tasks = listOf(running))
        assertEquals(queue, TaskPolicy.reconcile(queue, at))
        val recovered = TaskPolicy.recoverInterrupted(queue, at)
        val attention = recovered.tasks.single()
        assertEquals(TaskState.NEEDS_ATTENTION, attention.state)
        assertEquals("interrupted: reconcile before retry", attention.reason)
        assertEquals(running.sessionId, attention.sessionId)
        assertEquals(Int.MAX_VALUE, attention.attempt)
        assertEquals(recovered, TaskPolicy.recoverInterrupted(recovered, at))
        assertEquals(recovered, TaskPolicy.finish(recovered, running.id,
            running.sessionId!!, SessionOutcome.SUCCEEDED, at))
        assertEquals("attempt counter exhausted", assertFailsWith<IllegalArgumentException> {
            TaskPolicy.retry(recovered, running.id, at)
        }.message)
        val idle = TaskPolicy.claim(recovered, emptySet(), at)
        assertNull(idle.task)
        assertEquals(recovered, idle.queue)
    }
}

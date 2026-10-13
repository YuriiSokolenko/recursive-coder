package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Pure, in-memory Task decisions. Real store, Git, locks and runners stay in TaskManagerTest. */
@Tag("unit")
class TaskPolicyUnitTest {
    private val at = "2026-10-13T10:00:00Z"

    private fun task(
        id: String,
        state: TaskState = TaskState.READY,
        priority: Int = 50,
        order: Long = 1,
        dependencies: List<String> = emptyList(),
        attempt: Int = 0,
        sessionId: String? = null,
        waitTurns: Long = 0,
        reason: String? = null
    ) = Task(
        id = id, requestTemplate = "{}", dependencies = dependencies,
        priority = priority, state = state, attempt = attempt, sessionId = sessionId,
        queueOrder = order, waitTurns = waitTurns, reason = reason
    )

    @Test fun legalTransitionsAreTableDrivenAndTimestamped() {
        val legal = listOf(
            TaskState.CREATED to TaskState.BLOCKED,
            TaskState.CREATED to TaskState.READY,
            TaskState.READY to TaskState.RUNNING,
            TaskState.READY to TaskState.PAUSED,
            TaskState.BLOCKED to TaskState.NEEDS_ATTENTION,
            TaskState.PAUSED to TaskState.READY,
            TaskState.RUNNING to TaskState.SUCCEEDED,
            TaskState.RUNNING to TaskState.FAILED,
            TaskState.RUNNING to TaskState.CANCELLED,
            TaskState.RUNNING to TaskState.NEEDS_ATTENTION,
            TaskState.FAILED to TaskState.BLOCKED,
            TaskState.CANCELLED to TaskState.BLOCKED,
            TaskState.NEEDS_ATTENTION to TaskState.BLOCKED
        )
        for ((from, to) in legal) {
            val before = task("one", state = from)
            val after = TaskPolicy.transition(before, to, "reason", 10, at)
            assertEquals(to, after.task.state, "$from -> $to")
            assertEquals(from, after.task.transitions.single().from)
            assertEquals(at, after.task.transitions.single().occurredAt)
            assertEquals("reason", after.task.reason)
            assertEquals(from, before.state, "Decision must not mutate the input")
        }
    }

    @Test fun illegalTransitionsAreRejectedBeforeChangingState() {
        val illegal = listOf(
            TaskState.CREATED to TaskState.SUCCEEDED,
            TaskState.READY to TaskState.SUCCEEDED,
            TaskState.PAUSED to TaskState.RUNNING,
            TaskState.RUNNING to TaskState.READY,
            TaskState.SUCCEEDED to TaskState.CANCELLED,
            TaskState.FAILED to TaskState.READY,
            TaskState.CANCELLED to TaskState.RUNNING,
            TaskState.NEEDS_ATTENTION to TaskState.SUCCEEDED
        )
        for ((from, to) in illegal) {
            val before = task("one", state = from)
            assertFailsWith<IllegalArgumentException>("$from -> $to") {
                TaskPolicy.transition(before, to, null, 1, at)
            }
            assertTrue(before.transitions.isEmpty())
        }
    }

    @Test fun unchangedReasonDoesNotAppendHistoryButChangedAttentionReasonDoes() {
        val attention = task("one", state = TaskState.NEEDS_ATTENTION, reason = "dependency:old:failed")
        val unchanged = TaskPolicy.transition(attention, TaskState.NEEDS_ATTENTION,
            attention.reason, 3, at)
        assertSame(attention, unchanged.task)
        val changed = TaskPolicy.transition(attention, TaskState.NEEDS_ATTENTION,
            "dependency:new:failed", 3, at)
        assertEquals(1, changed.task.transitions.size)
        assertEquals("dependency:new:failed", changed.task.reason)
    }

    @Test fun dependencyGraphIsTopologicalAndRejectsInvalidEdits() {
        val a = task("a", state = TaskState.SUCCEEDED)
        val b = task("b", state = TaskState.BLOCKED, dependencies = listOf("a"))
        val c = task("c", state = TaskState.BLOCKED, dependencies = listOf("b"))
        assertEquals(listOf("a", "b", "c"), validateGraph(listOf(c, b, a)))

        val invalid = listOf(
            listOf(a, a),
            listOf(a, b.copy(dependencies = listOf("a", "a"))),
            listOf(a, b.copy(dependencies = listOf("missing"))),
            listOf(a.copy(dependencies = listOf("c")), b, c),
            listOf(a.copy(dependencies = listOf("a")))
        )
        for (graph in invalid) {
            assertFailsWith<IllegalArgumentException> { validateGraph(graph) }
        }
        // Edits are also rejected atomically: the original snapshot is untouched.
        val queue = TaskQueue(tasks = listOf(a, b, c))
        assertFailsWith<IllegalStateException> {
            TaskPolicy.replaceDependencies(queue, "a", listOf("c"), at)
        }
        assertTrue(queue.tasks[0].dependencies.isEmpty())
    }

    @Test fun enqueuingAndChangingDependenciesRejectInvalidDomainInputs() {
        val empty = TaskQueue()
        val started = TaskPolicy.enqueue(empty, "root", "{}", 50, emptyList(), at)
        assertEquals(TaskState.READY, started.tasks.single().state)
        assertEquals(0L, empty.nextOrder)
        assertEquals(2L, started.nextOrder) // Initial entry and first ready position.
        assertEquals(at, started.tasks.single().transitions.last().occurredAt)
        assertFailsWith<IllegalArgumentException> {
            TaskPolicy.enqueue(started, "root", "{}", 50, emptyList(), at)
        }
        assertFailsWith<IllegalArgumentException> {
            TaskPolicy.enqueue(started, "bad/id", "{}", 50, emptyList(), at)
        }
        assertFailsWith<IllegalArgumentException> {
            TaskPolicy.enqueue(started, "new", "{}", 101, emptyList(), at)
        }
        assertFailsWith<IllegalArgumentException> {
            TaskPolicy.enqueue(started, "new", "{}", 50, listOf("unknown"), at)
        }
        assertFailsWith<IllegalArgumentException> {
            TaskPolicy.replaceDependencies(started, "root", listOf("root"), at)
        }
    }

    @Test fun dependencyFailurePropagatesAndRetryWakesDependentsInOneDecision() {
        val queue = TaskQueue(tasks = listOf(
            task("grandchild", state = TaskState.BLOCKED, order = 3, dependencies = listOf("child")),
            task("child", state = TaskState.BLOCKED, order = 2, dependencies = listOf("parent")),
            task("parent", state = TaskState.FAILED, order = 1)
        ), nextOrder = 3)
        val failed = TaskPolicy.reconcile(queue, at)
        assertEquals(TaskState.NEEDS_ATTENTION, failed.tasks[1].state)
        assertEquals("dependency:parent:failed", failed.tasks[1].reason)
        assertEquals("dependency:child:needs-attention", failed.tasks[0].reason)
        assertFailsWith<IllegalArgumentException> { TaskPolicy.retry(failed, "child", at) }

        val retried = TaskPolicy.retry(failed, "parent", at)
        assertEquals(TaskState.READY, retried.tasks[2].state)
        assertEquals(TaskState.BLOCKED, retried.tasks[1].state)
        assertEquals(TaskState.BLOCKED, retried.tasks[0].state)
        assertEquals(0, retried.tasks[2].attempt)
        val claimed = TaskPolicy.claim(retried, emptySet(), at)
        val succeeded = TaskPolicy.finish(claimed.queue, "parent",
            claimed.task!!.sessionId!!, "succeeded", at)
        assertEquals(TaskState.READY, succeeded.tasks[1].state)
        assertEquals(TaskState.BLOCKED, succeeded.tasks[0].state)
    }

    @Test fun priorityAndFIFOOrderingIsDeterministic() {
        val queue = TaskQueue(tasks = listOf(
            task("first", priority = 50, order = 1),
            task("second", priority = 50, order = 2),
            task("high", priority = 90, order = 3)
        ), nextOrder = 3)
        val high = TaskPolicy.claim(queue, emptySet(), at)
        assertEquals("high", high.task!!.id)
        assertEquals(1L, high.queue.dispatches)
        assertEquals(1L, high.queue.tasks.first().waitTurns)
        assertEquals(1L, high.queue.tasks[1].waitTurns)
        assertEquals(0L, queue.dispatches)
        val next = TaskPolicy.claim(high.queue, emptySet(), at)
        assertEquals("first", next.task!!.id)
        assertEquals("second", TaskPolicy.claim(next.queue, emptySet(), at).task!!.id)
        assertNull(TaskPolicy.claim(next.queue, setOf("second"), at).task)
    }

    @Test fun oldestTaskIsSelectedOnEveryEighthDispatchEvenWithHighPriorityArrivals() {
        var queue = TaskQueue(nextOrder = 1, tasks = listOf(task("old", priority = 0, order = 1)))
        val selected = mutableListOf<String>()
        repeat(8) { dispatch ->
            queue = TaskPolicy.enqueue(queue, "new$dispatch", "{}", 100, emptyList(), at)
            val claim = TaskPolicy.claim(queue, emptySet(), at)
            selected += claim.task!!.id
            queue = claim.queue
        }
        assertEquals((0..6).map { "new$it" } + "old", selected)
        assertEquals(8L, queue.dispatches)
    }

    @Test fun waitCreditIsCappedAndExcludedRunningTaskCannotBeReclaimed() {
        val queue = TaskQueue(tasks = listOf(
            task("aged", priority = 0, order = 1, waitTurns = 1000),
            task("waiting", priority = 100, order = 2, waitTurns = 1000),
            task("fresh", priority = 100, order = 3)
        ), nextOrder = 3)
        val selected = TaskPolicy.claim(queue, setOf("aged"), at)
        assertEquals("waiting", selected.task!!.id)
        assertEquals(1000L, selected.queue.tasks[0].waitTurns)
        val allExcluded = TaskPolicy.claim(queue, setOf("aged", "waiting", "fresh"), at)
        assertNull(allExcluded.task)
        assertEquals(queue.dispatches, allExcluded.queue.dispatches)
    }

    @Test fun pauseResumePreservesOnlyAlreadyReadyPositions() {
        val queue = TaskQueue(nextOrder = 3, tasks = listOf(
            task("waiting", priority = 50, order = 1, waitTurns = 5),
            task("new", state = TaskState.BLOCKED, order = 2, dependencies = listOf("root")),
            task("root", state = TaskState.RUNNING, order = 3,
                attempt = 1, sessionId = "session-root-1")
        ))
        val paused = TaskPolicy.pause(queue, at)
        assertTrue(paused.paused)
        assertTrue(paused.tasks[0].preserveOrderOnResume)
        assertFalse(paused.tasks[1].preserveOrderOnResume)
        assertEquals(TaskState.RUNNING, paused.tasks[2].state)
        assertNull(TaskPolicy.claim(paused, emptySet(), at).task)
        val whilePaused = TaskPolicy.finish(paused, "root", "session-root-1", "succeeded", at)
        assertEquals(TaskState.PAUSED, whilePaused.tasks[1].state)
        val resumed = TaskPolicy.resume(whilePaused, at)
        assertEquals(1L, resumed.tasks[0].queueOrder)
        assertEquals(5L, resumed.tasks[0].waitTurns)
        assertEquals(TaskState.READY, resumed.tasks[1].state)
        assertTrue(resumed.tasks[1].queueOrder > 3)
        assertEquals(0L, resumed.tasks[1].waitTurns)
        assertEquals("waiting", TaskPolicy.claim(resumed, emptySet(), at).task!!.id)
    }

    @Test fun cancellationAndLateCompletionCannotOverrideTerminalStateOrNewOwner() {
        val initial = TaskQueue(tasks = listOf(task("one", order = 1)), nextOrder = 1)
        val first = TaskPolicy.claim(initial, emptySet(), at)
        val oldSession = first.task!!.sessionId!!
        val cancelled = TaskPolicy.cancel(first.queue, "one", at)
        assertEquals(TaskState.CANCELLED, cancelled.tasks.single().state)
        assertEquals(cancelled, TaskPolicy.finish(cancelled, "one", oldSession, "succeeded", at))
        assertEquals(cancelled, TaskPolicy.cancel(cancelled, "one", at))
        val retried = TaskPolicy.retry(cancelled, "one", at)
        assertNull(retried.tasks.single().sessionId)
        val second = TaskPolicy.claim(retried, emptySet(), at)
        assertEquals(2, second.task!!.attempt)
        assertNotEquals(oldSession, second.task!!.sessionId)
        assertEquals(second.queue, TaskPolicy.finish(second.queue, "one", oldSession, "succeeded", at))
        val complete = TaskPolicy.finish(second.queue, "one",
            second.task!!.sessionId!!, "succeeded", at)
        assertEquals(TaskState.SUCCEEDED, complete.tasks.single().state)
        assertEquals("succeeded", complete.tasks.single().lastResultStatus)
        assertEquals(complete, TaskPolicy.cancel(complete, "one", at))
        assertFailsWith<IllegalArgumentException> { TaskPolicy.retry(complete, "one", at) }
    }

    @Test fun recoveryRequiresAttentionWithoutReplayingSessions() {
        val claimed = TaskPolicy.claim(TaskQueue(tasks = listOf(task("one")), nextOrder = 1),
            emptySet(), at)
        val recovered = TaskPolicy.recoverInterrupted(claimed.queue, at)
        assertEquals(TaskState.NEEDS_ATTENTION, recovered.tasks.single().state)
        assertEquals(claimed.task!!.sessionId, recovered.tasks.single().sessionId)
        assertEquals(recovered, TaskPolicy.recoverInterrupted(recovered, at))
        assertEquals(recovered, TaskPolicy.interrupt(recovered,
            "one", claimed.task!!.sessionId!!, "too late", at))
        val retried = TaskPolicy.retry(recovered, "one", at)
        assertEquals(TaskState.READY, retried.tasks.single().state)
        assertEquals(1, retried.tasks.single().attempt)
        assertEquals(null, retried.tasks.single().sessionId)
    }

    @Test fun boundedHistoryRetainsMostRecent128EventsWithDeterministicTimes() {
        var value = task("one")
        var order = 1L
        repeat(140) { turn ->
            val state = if (turn % 2 == 0) TaskState.PAUSED else TaskState.READY
            val change = TaskPolicy.transition(value, state, null, order, "instant-$turn")
            value = change.task
            order = change.nextOrder
        }
        assertEquals(128, value.transitions.size)
        assertEquals(12L, value.prunedTransitions)
        assertEquals("instant-12", value.transitions.first().occurredAt)
        assertEquals("instant-139", value.transitions.last().occurredAt)
    }
}

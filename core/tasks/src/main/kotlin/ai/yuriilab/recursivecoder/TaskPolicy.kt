package ai.yuriilab.recursivecoder

/**
 * Task domain model and deterministic scheduling decisions.
 *
 * No persistence, system clock, threads, session execution, or JSON belongs in this file.
 * Each decision accepts a snapshot and a caller-provided timestamp, and returns a new snapshot.
 */

/** A cancellation is terminal; PAUSED represents a queued task, never a suspended session. */
enum class TaskState(val wire: String) {
    CREATED("created"), READY("ready"), BLOCKED("blocked"), RUNNING("running"),
    PAUSED("paused"), SUCCEEDED("succeeded"), FAILED("failed"),
    NEEDS_ATTENTION("needs-attention"), CANCELLED("cancelled");

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wire == value }
            ?: error("Unknown task state")
    }
}

data class TaskTransition(
    val from: TaskState?,
    val to: TaskState,
    val occurredAt: String,
    val attempt: Int,
    val sessionId: String?,
    val reason: String?
)

/** The template is an immutable SessionRequest v1 snapshot, not a path to a mutable request. */
data class Task(
    val id: String,
    val requestTemplate: String,
    val dependencies: List<String>,
    val priority: Int,
    val state: TaskState = TaskState.CREATED,
    val attempt: Int = 0,
    val sessionId: String? = null,
    val lastResultStatus: SessionOutcome? = null,
    val reason: String? = null,
    val queueOrder: Long = 0,
    val waitTurns: Long = 0,
    val preserveOrderOnResume: Boolean = false,
    val prunedTransitions: Long = 0,
    val transitions: List<TaskTransition> = emptyList()
)

internal val safeTaskId = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")

/** Immutable queue state published only for implementations of the TaskRepository port. */
data class TaskQueue(
    val paused: Boolean = false,
    val nextOrder: Long = 0,
    val dispatches: Long = 0,
    val tasks: List<Task> = emptyList()
)

internal data class TaskClaim(val queue: TaskQueue, val task: Task?)
internal data class TaskUpdate(val task: Task, val nextOrder: Long)

/** Validation entrypoint for persistence adapters decoding TaskQueue snapshots. */
fun validateStoredTasks(tasks: List<Task>) {
    require(tasks.all { safeTaskId.matches(it.id) && it.priority in 0..100 &&
        it.attempt >= 0 && it.waitTurns >= 0 && it.queueOrder >= 0 &&
        it.prunedTransitions >= 0 })
    validateGraph(tasks)
}

/** Internal DAG traversal; persistent adapters only need validateStoredTasks(). */
internal fun validateGraph(tasks: List<Task>): List<String> {
    val byId = tasks.associateBy { it.id }
    require(byId.size == tasks.size) { "Duplicate task ID" }
    val marks = mutableMapOf<String, Int>()
    val order = ArrayList<String>(tasks.size)
    val stack = ArrayDeque<Pair<String, Int>>()
    for (task in tasks) {
        if (marks[task.id] == 2) continue
        stack.addLast(task.id to 0)
        while (stack.isNotEmpty()) {
            val (id, offset) = stack.removeLast()
            if (marks[id] == 2) continue
            val dependencies = byId.getValue(id).dependencies
            if (offset == 0) {
                require(dependencies.size == dependencies.toSet().size) { "Duplicate dependency" }
                marks[id] = 1
            }
            if (offset == dependencies.size) {
                marks[id] = 2
                order.add(id)
                continue
            }
            stack.addLast(id to (offset + 1))
            val child = dependencies[offset]
            require(byId.containsKey(child)) { "Unknown dependency: " + child }
            when (marks[child]) {
                1 -> error("Dependency cycle detected")
                2 -> Unit
                else -> stack.addLast(child to 0)
            }
        }
    }
    return order
}

/**
 * Pure Task policy. Ownership checks, dependency wake-up and fairness are decided here.
 * The caller is responsible for serializing the returned snapshot inside one transaction.
 */
internal object TaskPolicy {
    private const val MAX_TRANSITIONS = 128
    private const val MAX_WAIT_TURNS = 1000L
    private const val OLDEST_TASK_INTERVAL = 8L

    private val allowed = mapOf(
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

    /** A same-state reason update is valid (e.g. a different failed prerequisite). */
    internal fun transition(
        current: Task, state: TaskState, reason: String?, nextOrder: Long, at: String
    ): TaskUpdate {
        if (current.state == state && current.reason == reason) return TaskUpdate(current, nextOrder)
        require(current.state == state || state in allowed.getValue(current.state)) {
            "Illegal task transition: ${current.state.wire} -> ${state.wire}"
        }
        val becameReady = state == TaskState.READY && current.state != TaskState.READY &&
            (current.state != TaskState.PAUSED || !current.preserveOrderOnResume)
        val newOrder = if (becameReady) nextOrder + 1 else nextOrder
        val events = current.transitions + TaskTransition(
            current.state, state, at, current.attempt, current.sessionId, reason
        )
        val removed = (events.size - MAX_TRANSITIONS).coerceAtLeast(0)
        return TaskUpdate(current.copy(
            state = state,
            reason = reason,
            queueOrder = if (becameReady) newOrder else current.queueOrder,
            waitTurns = if (becameReady) 0 else current.waitTurns,
            preserveOrderOnResume = state == TaskState.PAUSED && current.state == TaskState.READY,
            prunedTransitions = current.prunedTransitions + removed,
            transitions = events.drop(removed)
        ), newOrder)
    }

    private fun TaskQueue.index(id: String): Int =
        tasks.indexOfFirst { it.id == id }.also { require(it >= 0) { "Unknown task" } }

    private fun TaskQueue.replace(index: Int, task: Task, order: Long = nextOrder): TaskQueue {
        val updated = tasks.toMutableList()
        updated[index] = task
        return copy(nextOrder = order, tasks = updated)
    }

    fun enqueue(
        queue: TaskQueue, id: String, requestTemplate: String,
        priority: Int, dependencies: List<String>, at: String
    ): TaskQueue {
        require(safeTaskId.matches(id)) { "Unsafe task ID" }
        require(priority in 0..100) { "Priority must be 0..100" }
        require(queue.tasks.none { it.id == id }) { "Task already exists" }
        require(dependencies.distinct().size == dependencies.size) { "Duplicate dependency" }
        require(dependencies.all { dep -> queue.tasks.any { it.id == dep } }) { "Unknown dependency" }
        val initial = Task(
            id = id, requestTemplate = requestTemplate, dependencies = dependencies.toList(),
            priority = priority, queueOrder = queue.nextOrder + 1,
            transitions = listOf(TaskTransition(null, TaskState.CREATED, at, 0, null, "enqueued"))
        )
        val next = queue.copy(nextOrder = queue.nextOrder + 1, tasks = queue.tasks + initial)
        validateGraph(next.tasks)
        return reconcile(next, at)
    }

    fun replaceDependencies(queue: TaskQueue, id: String, dependencies: List<String>, at: String): TaskQueue {
        val index = queue.index(id)
        require(queue.tasks[index].attempt == 0) { "Cannot edit dependencies after execution" }
        val next = queue.replace(index, queue.tasks[index].copy(dependencies = dependencies.toList()))
        validateGraph(next.tasks)
        return reconcile(next, at)
    }

    fun pause(queue: TaskQueue, at: String): TaskQueue = reconcile(queue.copy(paused = true), at)
    fun resume(queue: TaskQueue, at: String): TaskQueue = reconcile(queue.copy(paused = false), at)

    fun cancel(queue: TaskQueue, id: String, at: String): TaskQueue {
        val index = queue.index(id)
        if (queue.tasks[index].state in setOf(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED))
            return queue
        val update = transition(queue.tasks[index], TaskState.CANCELLED,
            "cancelled by user", queue.nextOrder, at)
        return reconcile(queue.replace(index, update.task, update.nextOrder), at)
    }

    fun cancelAll(queue: TaskQueue, at: String): TaskQueue {
        val tasks = queue.tasks.map { task ->
            if (task.state in setOf(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED)) task
            else transition(task, TaskState.CANCELLED, "global cancellation", queue.nextOrder, at).task
        }
        return reconcile(queue.copy(paused = true, tasks = tasks), at)
    }

    fun retry(queue: TaskQueue, id: String, at: String): TaskQueue {
        val index = queue.index(id)
        val current = queue.tasks[index]
        require(current.state in setOf(TaskState.FAILED, TaskState.CANCELLED,
            TaskState.NEEDS_ATTENTION)) { "Only terminal/attention tasks can be retried" }
        require(current.state != TaskState.NEEDS_ATTENTION ||
            current.reason?.startsWith("dependency:") != true) {
            "Retry the failed prerequisite first; this task is blocked by a dependency"
        }
        // The old session is historical; the next claim creates a different owner ID.
        val update = transition(current.copy(sessionId = null), TaskState.BLOCKED,
            "manual retry", queue.nextOrder, at)
        return reconcile(queue.replace(index, update.task, update.nextOrder), at)
    }

    fun claim(queue: TaskQueue, excludedTaskIds: Set<String>, at: String): TaskClaim {
        val ready = reconcile(queue, at)
        if (ready.paused) return TaskClaim(ready, null)
        val eligible = ready.tasks.withIndex().filter {
            it.value.state == TaskState.READY && it.value.id !in excludedTaskIds
        }
        val selected = if (ready.dispatches % OLDEST_TASK_INTERVAL == OLDEST_TASK_INTERVAL - 1) {
            eligible.minByOrNull { it.value.queueOrder }
        } else {
            eligible.maxWithOrNull(compareBy<IndexedValue<Task>> {
                it.value.priority.toLong() + it.value.waitTurns.coerceAtMost(MAX_WAIT_TURNS) * 10L
            }.thenBy { -it.value.queueOrder })
        } ?: return TaskClaim(ready, null)

        val tasks = ready.tasks.mapIndexed { index, task ->
            if (index != selected.index && task.state == TaskState.READY)
                task.copy(waitTurns = (task.waitTurns + 1).coerceAtMost(MAX_WAIT_TURNS))
            else task
        }.toMutableList()
        val current = tasks[selected.index]
        val attempt = current.attempt + 1
        val owner = current.copy(attempt = attempt, sessionId = "session-" + current.id + "-" + attempt)
        val update = transition(owner, TaskState.RUNNING,
            "claimed by local scheduler", ready.nextOrder, at)
        tasks[selected.index] = update.task
        return TaskClaim(ready.copy(
            nextOrder = update.nextOrder, dispatches = ready.dispatches + 1,
            tasks = tasks.toList()
        ), update.task)
    }

    fun finish(queue: TaskQueue, id: String, sessionId: String, outcome: SessionOutcome, at: String): TaskQueue {
        val index = queue.index(id)
        val current = queue.tasks[index]
        if (current.state != TaskState.RUNNING || current.sessionId != sessionId) return queue
        val target = when (outcome) {
            SessionOutcome.SUCCEEDED -> TaskState.SUCCEEDED
            SessionOutcome.CANCELLED -> TaskState.CANCELLED
            SessionOutcome.REJECTED, SessionOutcome.FAILED, SessionOutcome.TIMED_OUT -> TaskState.FAILED
        }
        val update = transition(current.copy(lastResultStatus = outcome), target,
            if (target == TaskState.SUCCEEDED) null else outcome.wire, queue.nextOrder, at)
        return reconcile(queue.replace(index, update.task, update.nextOrder), at)
    }

    fun interrupt(queue: TaskQueue, id: String, sessionId: String, reason: String, at: String): TaskQueue {
        val index = queue.index(id)
        val current = queue.tasks[index]
        if (current.state != TaskState.RUNNING || current.sessionId != sessionId) return queue
        val update = transition(current, TaskState.NEEDS_ATTENTION, reason, queue.nextOrder, at)
        return reconcile(queue.replace(index, update.task, update.nextOrder), at)
    }

    fun recoverInterrupted(queue: TaskQueue, at: String): TaskQueue {
        val tasks = queue.tasks.map { task ->
            if (task.state != TaskState.RUNNING) task
            else transition(task, TaskState.NEEDS_ATTENTION,
                "interrupted: reconcile before retry", queue.nextOrder, at).task
        }
        return reconcile(queue.copy(tasks = tasks), at)
    }

    /** Topological order ensures that a parent wakes a dependent in the same decision. */
    fun reconcile(queue: TaskQueue, at: String): TaskQueue {
        val order = validateGraph(queue.tasks)
        val indexById = queue.tasks.indices.associateBy { queue.tasks[it].id }
        val tasks = queue.tasks.toMutableList()
        var nextOrder = queue.nextOrder
        for (id in order) {
            val index = indexById.getValue(id)
            val task = tasks[index]
            if (task.state in setOf(TaskState.RUNNING, TaskState.SUCCEEDED,
                    TaskState.FAILED, TaskState.CANCELLED)) continue
            if (task.state == TaskState.NEEDS_ATTENTION &&
                task.reason?.startsWith("dependency:") != true) continue
            val prerequisites = task.dependencies.map { tasks[indexById.getValue(it)] }
            val missing = prerequisites.firstOrNull {
                it.state in setOf(TaskState.FAILED, TaskState.CANCELLED, TaskState.NEEDS_ATTENTION)
            }
            val next = when {
                missing != null -> TaskState.NEEDS_ATTENTION
                queue.paused -> TaskState.PAUSED
                prerequisites.all { it.state == TaskState.SUCCEEDED } -> TaskState.READY
                else -> TaskState.BLOCKED
            }
            val reason = if (missing == null) null else "dependency:" + missing.id + ":" + missing.state.wire
            val updated = transition(task, next, reason, nextOrder, at)
            tasks[index] = updated.task
            nextOrder = updated.nextOrder
        }
        return queue.copy(nextOrder = nextOrder, tasks = tasks.toList())
    }
}

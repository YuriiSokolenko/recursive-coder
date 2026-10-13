package ai.yuriilab.recursivecoder

import java.time.Clock

/**
 * Read-only queue queries share a single business intent: inspect task state.
 * Each call reads a serialized repository snapshot without opening a write transaction.
 */
interface TaskQueriesUseCase {
    fun list(): List<Task>
    fun show(id: String): Task
    fun isPaused(): Boolean
}

class DefaultTaskQueriesUseCase(private val repository: TaskRepository) : TaskQueriesUseCase {
    override fun list(): List<Task> = repository.read { it.tasks.toList() }

    override fun show(id: String): Task = repository.read { queue ->
        queue.tasks.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown task")
    }

    override fun isPaused(): Boolean = repository.read { it.paused }
}

private fun TaskQueue.requireTask(id: String): Task =
    tasks.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown task")

/** Cancel one task and reconcile its dependents in the same transaction. */
fun interface CancelTaskUseCase {
    operator fun invoke(id: String): Task
}

class DefaultCancelTaskUseCase(
    private val repository: TaskRepository,
    private val clock: Clock = Clock.systemUTC()
) : CancelTaskUseCase {
    override fun invoke(id: String): Task = repository.update { queue ->
        val next = TaskPolicy.cancel(queue, id, clock.instant().toString())
        TaskMutation(next, next.requireTask(id))
    }
}

/** Retry a terminal or reconciled attempt, subject to prerequisite policy. */
fun interface RetryTaskUseCase {
    operator fun invoke(id: String): Task
}

class DefaultRetryTaskUseCase(
    private val repository: TaskRepository,
    private val clock: Clock = Clock.systemUTC()
) : RetryTaskUseCase {
    override fun invoke(id: String): Task = repository.update { queue ->
        val next = TaskPolicy.retry(queue, id, clock.instant().toString())
        TaskMutation(next, next.requireTask(id))
    }
}

/** Pause future dispatch without cancelling already running attempts. */
fun interface PauseTaskQueueUseCase {
    operator fun invoke()
}

class DefaultPauseTaskQueueUseCase(
    private val repository: TaskRepository,
    private val clock: Clock = Clock.systemUTC()
) : PauseTaskQueueUseCase {
    override fun invoke(): Unit = repository.update {
        TaskMutation(TaskPolicy.pause(it, clock.instant().toString()), Unit)
    }
}

/** Resume eligible tasks after applying dependency reconciliation. */
fun interface ResumeTaskQueueUseCase {
    operator fun invoke()
}

class DefaultResumeTaskQueueUseCase(
    private val repository: TaskRepository,
    private val clock: Clock = Clock.systemUTC()
) : ResumeTaskQueueUseCase {
    override fun invoke(): Unit = repository.update {
        TaskMutation(TaskPolicy.resume(it, clock.instant().toString()), Unit)
    }
}

/** Edit prerequisites only before a task has ever been claimed. */
fun interface ReplaceTaskDependenciesUseCase {
    operator fun invoke(id: String, dependencies: List<String>): Task
}

class DefaultReplaceTaskDependenciesUseCase(
    private val repository: TaskRepository,
    private val clock: Clock = Clock.systemUTC()
) : ReplaceTaskDependenciesUseCase {
    override fun invoke(id: String, dependencies: List<String>): Task = repository.update { queue ->
        val next = TaskPolicy.replaceDependencies(queue, id, dependencies, clock.instant().toString())
        TaskMutation(next, next.requireTask(id))
    }
}

/** Global cancellation also pauses the queue as one atomic policy decision. */
fun interface CancelAllTasksUseCase {
    operator fun invoke()
}

class DefaultCancelAllTasksUseCase(
    private val repository: TaskRepository,
    private val clock: Clock = Clock.systemUTC()
) : CancelAllTasksUseCase {
    override fun invoke(): Unit = repository.update {
        TaskMutation(TaskPolicy.cancelAll(it, clock.instant().toString()), Unit)
    }
}

/**
 * Scheduler-only atomic claim. Excluded IDs include previous generations of
 * attempts whose workers are still shutting down; never claim outside update.
 */
fun interface ClaimNextTaskUseCase {
    operator fun invoke(excludedTaskIds: Set<String>): Task?
}

class DefaultClaimNextTaskUseCase(
    private val repository: TaskRepository,
    private val clock: Clock = Clock.systemUTC()
) : ClaimNextTaskUseCase {
    override fun invoke(excludedTaskIds: Set<String>): Task? = repository.update { queue ->
        val decision = TaskPolicy.claim(queue, excludedTaskIds, clock.instant().toString())
        TaskMutation(decision.queue, decision.task)
    }
}

/** Only the currently owned session can commit its terminal result. */
fun interface CompleteTaskAttemptUseCase {
    operator fun invoke(id: String, sessionId: String, resultStatus: String): Task
}

class DefaultCompleteTaskAttemptUseCase(
    private val repository: TaskRepository,
    private val clock: Clock = Clock.systemUTC()
) : CompleteTaskAttemptUseCase {
    override fun invoke(id: String, sessionId: String, resultStatus: String): Task {
        // Validate at the boundary, before opening a repository transaction.
        val outcome = SessionOutcome.fromWire(resultStatus)
        return repository.update { queue ->
            val next = TaskPolicy.finish(queue, id, sessionId, outcome, clock.instant().toString())
            TaskMutation(next, next.requireTask(id))
        }
    }
}

/** Scheduler shutdown is a recoverable interruption, never a user cancellation. */
fun interface InterruptTaskAttemptUseCase {
    operator fun invoke(id: String, sessionId: String, reason: String): Task
}

class DefaultInterruptTaskAttemptUseCase(
    private val repository: TaskRepository,
    private val clock: Clock = Clock.systemUTC()
) : InterruptTaskAttemptUseCase {
    override fun invoke(id: String, sessionId: String, reason: String): Task =
        repository.update { queue ->
            val next = TaskPolicy.interrupt(queue, id, sessionId, reason, clock.instant().toString())
            TaskMutation(next, next.requireTask(id))
        }
}

/** The caller MUST hold TaskRepository.withSchedulerLock across recovery and dispatch. */
fun interface RecoverInterruptedAttemptsUseCase {
    operator fun invoke()
}

class DefaultRecoverInterruptedAttemptsUseCase(
    private val repository: TaskRepository,
    private val clock: Clock = Clock.systemUTC()
) : RecoverInterruptedAttemptsUseCase {
    override fun invoke(): Unit = repository.update {
        TaskMutation(TaskPolicy.recoverInterrupted(it, clock.instant().toString()), Unit)
    }
}

/** Project a claimed task into its per-attempt request and durable output root. */
fun interface PrepareTaskAttemptUseCase {
    operator fun invoke(task: Task): SessionRequest
}

class DefaultPrepareTaskAttemptUseCase(private val repository: TaskRepository) : PrepareTaskAttemptUseCase {
    override fun invoke(task: Task): SessionRequest =
        TaskRequestMapper.forAttempt(task, repository.outputRoot(task))
}

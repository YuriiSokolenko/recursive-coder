package ai.yuriilab.recursivecoder

import java.time.Clock

/**
 * Submit one immutable SessionRequest v1 snapshot into the queue.
 * This contract owns no CLI parsing, persistence implementation or DI framework.
 */
interface EnqueueTaskUseCase {
    operator fun invoke(
        requestJson: String,
        priority: Int = 50,
        dependencies: List<String> = emptyList()
    ): Task
}

/**
 * Validation precedes the transaction; policy and insertion run under one repository lock.
 * A rejected submission never publishes a partially updated queue.
 */
class DefaultEnqueueTaskUseCase(
    private val repository: TaskRepository,
    private val clock: Clock = Clock.systemUTC()
) : EnqueueTaskUseCase {
    override fun invoke(requestJson: String, priority: Int, dependencies: List<String>): Task {
        val submission = TaskRequestMapper.submission(requestJson)
        val id = submission.taskId
        require(safeTaskId.matches(id)) { "Unsafe task ID" }
        require(priority in 0..100) { "Priority must be 0..100" }
        return repository.update { queue ->
            val next = TaskPolicy.enqueue(
                queue, id, submission.template, priority, dependencies, clock.instant().toString()
            )
            TaskMutation(next, next.tasks.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown task"))
        }
    }
}

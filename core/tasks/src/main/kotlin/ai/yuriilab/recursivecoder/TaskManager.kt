package ai.yuriilab.recursivecoder

import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Task use cases apply pure decisions inside a repository-owned atomic transaction. */
class TaskService(val store: TaskRepository, private val clock: Clock = Clock.systemUTC()) {
    fun list(): List<Task> = store.read { it.tasks.toList() }
    fun show(id: String): Task = store.read { it.find(id) }
    fun isPaused(): Boolean = store.read { it.paused }

    private fun now(): String = clock.instant().toString()
    private fun TaskQueue.find(id: String): Task =
        tasks.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown task")

    fun enqueue(requestJson: String, priority: Int = 50, dependencies: List<String> = emptyList()): Task {
        val submission = TaskRequestMapper.submission(requestJson)
        val id = submission.taskId
        require(safeTaskId.matches(id)) { "Unsafe task ID" }
        require(priority in 0..100) { "Priority must be 0..100" }
        return store.update { queue ->
            val next = TaskPolicy.enqueue(queue, id, submission.template, priority, dependencies, now())
            TaskMutation(next, next.find(id))
        }
    }

    /** Only tasks not yet attempted can change their prerequisites. */
    fun replaceDependencies(id: String, dependencies: List<String>): Task = store.update { queue ->
        val next = TaskPolicy.replaceDependencies(queue, id, dependencies, now())
        TaskMutation(next, next.find(id))
    }

    fun pause(): Unit = store.update { TaskMutation(TaskPolicy.pause(it, now()), Unit) }
    fun resume(): Unit = store.update { TaskMutation(TaskPolicy.resume(it, now()), Unit) }

    fun cancel(id: String): Task = store.update { queue ->
        val next = TaskPolicy.cancel(queue, id, now())
        TaskMutation(next, next.find(id))
    }

    fun cancelAll(): Unit = store.update { TaskMutation(TaskPolicy.cancelAll(it, now()), Unit) }

    fun retry(id: String): Task = store.update { queue ->
        val next = TaskPolicy.retry(queue, id, now())
        TaskMutation(next, next.find(id))
    }

    /**
     * Scheduler-only integration API: atomically claim the next eligible Task.
     * Kept public for cross-module adapter contracts; CLI callers should use TaskScheduler.
     * This must remain a single repository transaction.
     */
    fun claim(excludedTaskIds: Set<String> = emptySet()): Task? = store.update { queue ->
        val decision = TaskPolicy.claim(queue, excludedTaskIds, now())
        TaskMutation(decision.queue, decision.task)
    }

    /** Scheduler-only completion; a stale session ID must never change a newer attempt. */
    fun finish(id: String, sessionId: String, resultStatus: String): Task {
        // Keep the adapter-facing signature but validate before any transaction.
        val outcome = SessionOutcome.fromWire(resultStatus)
        return store.update { queue ->
            val next = TaskPolicy.finish(queue, id, sessionId, outcome, now())
            TaskMutation(next, next.find(id))
        }
    }

    /** Scheduler-only failure recovery: a stopped attempt is not user cancellation. */
    fun interruptAttempt(id: String, sessionId: String, reason: String): Task =
        store.update { queue ->
            val next = TaskPolicy.interrupt(queue, id, sessionId, reason, now())
            TaskMutation(next, next.find(id))
        }

    /** Scheduler-only recovery; caller MUST hold the repository's exclusive scheduler lease. */
    fun recoverInterrupted(): Unit = store.update {
        TaskMutation(TaskPolicy.recoverInterrupted(it, now()), Unit)
    }

    /** Scheduler-only invocation preparation; the JSON projection is a mapper. */
    fun requestFor(task: Task): SessionRequest =
        TaskRequestMapper.forAttempt(task, store.outputRoot(task))
}

fun interface TaskRunner {
    fun execute(request: SessionRequest, cancellation: Cancellation): String
}

data class SchedulerReport(val launched: Int, val completed: Int, val paused: Boolean)

/**
 * A process-wide scheduler lock protects recovery and dispatch. Active attempts
 * are keyed by session ID, not task ID: a user may retry a cancelled task while
 * its previous worker is still shutting down. Both generations consume slots.
 */
class TaskScheduler(
    private val service: TaskService,
    private val runner: TaskRunner,
    private val pollMillis: Long = 30
) {
    private data class RunningAttempt(
        val taskId: String,
        val cancellation: Cancellation,
        val future: Future<*>
    )
    private val stopping = AtomicBoolean(false)
    private val active = ConcurrentHashMap<String, RunningAttempt>()

    /**
     * A scheduler stop is NOT equivalent to a user task cancellation.
     * In-flight attempts enter needs-attention, never silently become cancelled.
     * Suitable for JVM shutdown hooks as well as an exception-exit path.
     */
    fun requestStop() {
        stopping.set(true)
        val inFlight = active.entries.toList()
        inFlight.forEach { it.value.cancellation.cancel() }
        inFlight.forEach { (sessionId, attempt) ->
            service.interruptAttempt(attempt.taskId, sessionId,
                "scheduler stopped: reconcile unfinished attempt before retry")
        }
    }

    fun runUntilIdle(concurrency: Int = 1): SchedulerReport {
        require(concurrency in 1..32) { "Concurrency must be 1..32" }
        require(pollMillis in 1..1000)
        return service.store.withSchedulerLock {
            if (stopping.get()) return@withSchedulerLock SchedulerReport(0, 0, service.isPaused())
            service.recoverInterrupted()
            val pool = Executors.newFixedThreadPool(concurrency)
            var launched = 0
            var completed = 0
            try {
                while (!stopping.get()) {
                    while (!stopping.get() && active.size < concurrency) {
                        val occupiedTaskIds = active.values.mapTo(mutableSetOf()) { it.taskId }
                        val task = service.claim(occupiedTaskIds) ?: break
                        val sessionId = task.sessionId!!
                        if (stopping.get()) {
                            service.interruptAttempt(task.id, sessionId,
                                "scheduler stopped before session started")
                            break
                        }
                        val cancellation = Cancellation()
                        val future = pool.submit {
                            cancellation.bind()
                            val status = try {
                                val current = service.show(task.id)
                                if (current.sessionId != sessionId ||
                                    current.state == TaskState.CANCELLED || stopping.get()) "cancelled"
                                else SessionOutcome.fromWire(runner.execute(service.requestFor(task), cancellation)).wire
                            } catch (_: InterruptedException) {
                                "cancelled"
                            } catch (_: Exception) {
                                "failed"
                            }
                            // Clear worker interruption before locking the durable store.
                            Thread.interrupted()
                            if (stopping.get()) {
                                service.interruptAttempt(task.id, sessionId,
                                    "scheduler stopped: reconcile unfinished attempt before retry")
                            } else {
                                service.finish(task.id, sessionId, status)
                            }
                        }
                        active[sessionId] = RunningAttempt(task.id, cancellation, future)
                        launched++
                        // A concurrent shutdown hook may have fired after the earlier check.
                        if (stopping.get()) requestStop()
                    }
                    if (stopping.get()) break
                    val states = service.list().associateBy { it.id }
                    active.forEach { (sessionId, attempt) ->
                        val task = states[attempt.taskId]
                        if (task?.state == TaskState.CANCELLED || task?.sessionId != sessionId)
                            attempt.cancellation.cancel()
                    }
                    val finishedIds = active.entries.filter { it.value.future.isDone }.map { it.key }
                    finishedIds.forEach { sessionId ->
                        val attempt = active.getValue(sessionId)
                        attempt.future.get()
                        active.remove(sessionId)
                        completed++
                    }
                    if (active.isEmpty()) {
                        if (service.isPaused() || service.list().none { it.state == TaskState.READY })
                            break
                    }
                    Thread.sleep(pollMillis)
                }
            } finally {
                // Also runs if the scheduler loop fails, without inventing a user cancellation.
                try {
                    if (active.isNotEmpty()) requestStop()
                } finally {
                    pool.shutdownNow()
                    // Keep the exclusive scheduler lock while any local worker remains alive.
                    // Otherwise a fresh scheduler could start concurrent work on the same store.
                    var interrupted = false
                    var terminated = false
                    while (!terminated) {
                        try {
                            terminated = pool.awaitTermination(1, TimeUnit.SECONDS)
                        } catch (_: InterruptedException) {
                            interrupted = true
                        }
                    }
                    active.clear()
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
            SchedulerReport(launched, completed, service.isPaused())
        }
    }
}


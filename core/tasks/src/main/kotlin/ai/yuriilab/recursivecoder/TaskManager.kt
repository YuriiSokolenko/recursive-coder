package ai.yuriilab.recursivecoder

import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Compatibility façade; new CLI and scheduler consumers inject use cases directly. */
class TaskService(
    val store: TaskRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val enqueueUseCase: EnqueueTaskUseCase = DefaultEnqueueTaskUseCase(store, clock)
) {
    internal val queries: TaskQueriesUseCase = DefaultTaskQueriesUseCase(store)
    internal val claimUseCase: ClaimNextTaskUseCase = DefaultClaimNextTaskUseCase(store, clock)
    internal val completeUseCase: CompleteTaskAttemptUseCase = DefaultCompleteTaskAttemptUseCase(store, clock)
    internal val interruptUseCase: InterruptTaskAttemptUseCase = DefaultInterruptTaskAttemptUseCase(store, clock)
    internal val recoverUseCase: RecoverInterruptedAttemptsUseCase =
        DefaultRecoverInterruptedAttemptsUseCase(store, clock)
    internal val prepareUseCase: PrepareTaskAttemptUseCase = DefaultPrepareTaskAttemptUseCase(store)
    private val cancelUseCase: CancelTaskUseCase = DefaultCancelTaskUseCase(store, clock)
    private val retryUseCase: RetryTaskUseCase = DefaultRetryTaskUseCase(store, clock)
    private val pauseUseCase: PauseTaskQueueUseCase = DefaultPauseTaskQueueUseCase(store, clock)
    private val resumeUseCase: ResumeTaskQueueUseCase = DefaultResumeTaskQueueUseCase(store, clock)
    private val replaceDependenciesUseCase: ReplaceTaskDependenciesUseCase =
        DefaultReplaceTaskDependenciesUseCase(store, clock)
    private val cancelAllUseCase: CancelAllTasksUseCase = DefaultCancelAllTasksUseCase(store, clock)

    fun list(): List<Task> = queries.list()
    fun show(id: String): Task = queries.show(id)
    fun isPaused(): Boolean = queries.isPaused()
    fun enqueue(requestJson: String, priority: Int = 50, dependencies: List<String> = emptyList()): Task =
        enqueueUseCase(requestJson, priority, dependencies)
    fun replaceDependencies(id: String, dependencies: List<String>): Task =
        replaceDependenciesUseCase(id, dependencies)
    fun pause(): Unit = pauseUseCase()
    fun resume(): Unit = resumeUseCase()
    fun cancel(id: String): Task = cancelUseCase(id)
    fun cancelAll(): Unit = cancelAllUseCase()
    fun retry(id: String): Task = retryUseCase(id)

    /** Adapter for callers retaining the original scheduler-facing methods. */
    fun claim(excludedTaskIds: Set<String> = emptySet()): Task? = claimUseCase(excludedTaskIds)
    fun finish(id: String, sessionId: String, resultStatus: String): Task =
        completeUseCase(id, sessionId, resultStatus)
    fun interruptAttempt(id: String, sessionId: String, reason: String): Task =
        interruptUseCase(id, sessionId, reason)
    fun recoverInterrupted(): Unit = recoverUseCase()
    fun requestFor(task: Task): SessionRequest = prepareUseCase(task)
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
    private val repository: TaskRepository,
    private val queries: TaskQueriesUseCase,
    private val claim: ClaimNextTaskUseCase,
    private val complete: CompleteTaskAttemptUseCase,
    private val interrupt: InterruptTaskAttemptUseCase,
    private val recover: RecoverInterruptedAttemptsUseCase,
    private val prepare: PrepareTaskAttemptUseCase,
    private val runner: TaskRunner,
    private val pollMillis: Long = 30
) {
    /** Compatibility constructor; the CLI uses the injected primary constructor. */
    constructor(service: TaskService, runner: TaskRunner, pollMillis: Long = 30) : this(
        service.store, service.queries, service.claimUseCase, service.completeUseCase,
        service.interruptUseCase, service.recoverUseCase, service.prepareUseCase, runner, pollMillis
    )

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
            interrupt(attempt.taskId, sessionId,
                "scheduler stopped: reconcile unfinished attempt before retry")
        }
    }

    fun runUntilIdle(concurrency: Int = 1): SchedulerReport {
        require(concurrency in 1..32) { "Concurrency must be 1..32" }
        require(pollMillis in 1..1000)
        return repository.withSchedulerLock {
            if (stopping.get()) return@withSchedulerLock SchedulerReport(0, 0, queries.isPaused())
            recover()
            val pool = Executors.newFixedThreadPool(concurrency)
            var launched = 0
            var completed = 0
            try {
                while (!stopping.get()) {
                    while (!stopping.get() && active.size < concurrency) {
                        val occupiedTaskIds = active.values.mapTo(mutableSetOf()) { it.taskId }
                        val task = claim(occupiedTaskIds) ?: break
                        val sessionId = task.sessionId!!
                        if (stopping.get()) {
                            interrupt(task.id, sessionId,
                                "scheduler stopped before session started")
                            break
                        }
                        val cancellation = Cancellation()
                        val future = pool.submit {
                            cancellation.bind()
                            val status = try {
                                val current = queries.show(task.id)
                                if (current.sessionId != sessionId ||
                                    current.state == TaskState.CANCELLED || stopping.get()) "cancelled"
                                else SessionOutcome.fromWire(runner.execute(prepare(task), cancellation)).wire
                            } catch (_: InterruptedException) {
                                "cancelled"
                            } catch (_: Exception) {
                                "failed"
                            }
                            // Clear worker interruption before locking the durable store.
                            Thread.interrupted()
                            if (stopping.get()) {
                                interrupt(task.id, sessionId,
                                    "scheduler stopped: reconcile unfinished attempt before retry")
                            } else {
                                complete(task.id, sessionId, status)
                            }
                        }
                        active[sessionId] = RunningAttempt(task.id, cancellation, future)
                        launched++
                        // A concurrent shutdown hook may have fired after the earlier check.
                        if (stopping.get()) requestStop()
                    }
                    if (stopping.get()) break
                    val states = queries.list().associateBy { it.id }
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
                        if (queries.isPaused() || queries.list().none { it.state == TaskState.READY })
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
            SchedulerReport(launched, completed, queries.isPaused())
        }
    }
}


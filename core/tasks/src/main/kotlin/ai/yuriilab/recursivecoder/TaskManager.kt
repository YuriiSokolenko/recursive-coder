package ai.yuriilab.recursivecoder

import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

/** Public JSON is an adapter projection, not part of the Task domain model. */
fun Task.toPublicJson(): JsonObject = buildJsonObject {
        put("taskId", id); put("state", state.wire); put("priority", priority)
        put("dependencies", buildJsonArray { dependencies.forEach { add(it) } })
        put("attempt", attempt)
        put("prunedTransitions", prunedTransitions)
        put("sessionId", sessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("lastResultStatus", lastResultStatus?.let(::JsonPrimitive) ?: JsonNull)
        put("reason", reason?.let(::JsonPrimitive) ?: JsonNull)
        put("transitions", buildJsonArray {
            transitions.forEach { step ->
                add(buildJsonObject {
                    put("from", step.from?.wire?.let(::JsonPrimitive) ?: JsonNull)
                    put("to", step.to.wire); put("at", step.occurredAt)
                    put("attempt", step.attempt)
                    put("sessionId", step.sessionId?.let(::JsonPrimitive) ?: JsonNull)
                    put("reason", step.reason?.let(::JsonPrimitive) ?: JsonNull)
                })
            }
        })
    }

/** Task use cases apply pure decisions inside a repository-owned atomic transaction. */
class TaskService(val store: TaskRepository, private val clock: Clock = Clock.systemUTC()) {
    fun list(): List<Task> = store.read { it.tasks.toList() }
    fun show(id: String): Task = store.read { it.find(id) }
    fun isPaused(): Boolean = store.read { it.paused }

    private fun now(): String = clock.instant().toString()
    private fun TaskQueue.find(id: String): Task =
        tasks.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown task")

    fun enqueue(requestJson: String, priority: Int = 50, dependencies: List<String> = emptyList()): Task {
        require(requestJson.toByteArray(StandardCharsets.UTF_8).size <= 262144) { "Request too large" }
        val request = SessionRequest.parse(requestJson)
        val id = request.task.getValue("taskId").jsonPrimitive.content
        require(safeTaskId.matches(id)) { "Unsafe task ID" }
        require(priority in 0..100) { "Priority must be 0..100" }
        return store.update { queue ->
            val next = TaskPolicy.enqueue(queue, id, request.raw.toString(), priority, dependencies, now())
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
    fun finish(id: String, sessionId: String, resultStatus: String): Task = store.update { queue ->
        val next = TaskPolicy.finish(queue, id, sessionId, resultStatus, now())
        TaskMutation(next, next.find(id))
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

    /** Scheduler-only invocation preparation; not part of the user-facing Task command API. */
    fun requestFor(task: Task): SessionRequest {
        require(task.state == TaskState.RUNNING && task.sessionId != null)
        val source = Json.parseToJsonElement(task.requestTemplate).jsonObject
        val runRoot = store.outputRoot(task)
        val invocation = source.getValue("invocation").jsonObject
        val adjustedInvocation = JsonObject(invocation + mapOf(
            "source" to JsonPrimitive("scheduler"),
            "attempt" to JsonPrimitive(task.attempt),
            "invocationId" to JsonPrimitive("invoke-" + task.id + "-" + task.attempt)
        ))
        val outputs = buildJsonObject {
            put("rootDir", runRoot.toString())
            put("resultPath", runRoot.resolve("result.json").toString())
            put("eventsPath", runRoot.resolve("events.jsonl").toString())
            put("checkpointDir", runRoot.resolve("checkpoints").toString())
            put("diagnosticsPath", runRoot.resolve("diagnostics.json").toString())
        }
        return SessionRequest.parse(JsonObject(source + mapOf(
            "requestId" to JsonPrimitive("request-" + task.id + "-" + task.attempt),
            "sessionId" to JsonPrimitive(task.sessionId),
            "invocation" to adjustedInvocation,
            "outputs" to outputs
        )).toString())
    }
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
                                else runner.execute(service.requestFor(task), cancellation)
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


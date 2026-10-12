package ai.yuriilab.recursivecoder

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.*

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
    val lastResultStatus: String? = null,
    val reason: String? = null,
    val queueOrder: Long = 0,
    val waitTurns: Long = 0,
    val preserveOrderOnResume: Boolean = false,
    val prunedTransitions: Long = 0,
    val transitions: List<TaskTransition> = emptyList()
) {
    fun toPublicJson(): JsonObject = buildJsonObject {
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
}

internal data class TaskDatabase(
    var paused: Boolean = false,
    var nextOrder: Long = 0,
    var dispatches: Long = 0,
    val tasks: MutableList<Task> = mutableListOf()
)

/**
 * Atomic snapshot transactions guarded by both a per-JVM mutex and an OS file lock.
 * The previous snapshot is never truncated: a killed writer leaves either the old
 * or the new complete snapshot. A stray .tmp file is not recovery authority.
 */
class TaskStore(directory: Path) {
    val directory: Path = directory.toAbsolutePath().normalize()
    private val snapshot = this.directory.resolve("tasks.json")
    private val temp = this.directory.resolve("tasks.json.tmp")
    private val guardFile = this.directory.resolve(".store.lock")

    init {
        require(!Files.isSymbolicLink(this.directory)) { "Task store cannot be a symlink" }
        Files.createDirectories(this.directory)
        require(Files.isDirectory(this.directory, LinkOption.NOFOLLOW_LINKS))
    }

    internal fun <T> transaction(write: Boolean = true, operation: (TaskDatabase) -> T): T {
        val mutex = localLocks.computeIfAbsent(directory) { ReentrantLock() }
        return mutex.withLock {
            require(!Files.isSymbolicLink(guardFile) && !Files.isSymbolicLink(snapshot))
            FileChannel.open(guardFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    val database = load()
                    val original = if (write) database.copy(tasks = database.tasks.toMutableList()) else null
                    val value = operation(database)
                    if (write && database != original) save(database)
                    value
                }
            }
        }
    }

    private fun load(): TaskDatabase {
        if (!Files.exists(snapshot, LinkOption.NOFOLLOW_LINKS)) return TaskDatabase()
        require(Files.size(snapshot) <= 64L * 1024 * 1024) { "Task store is too large" }
        return try {
            val obj = Json.parseToJsonElement(Files.readString(snapshot)).jsonObject
            require(obj.getValue("version").jsonPrimitive.int == 1)
            val tasks = obj.getValue("tasks").jsonArray.map { element ->
                val item = element.jsonObject
                val transitions = item.getValue("transitions").jsonArray.map { event ->
                    val step = event.jsonObject
                    TaskTransition(
                        step.optionalText("from")?.let(TaskState::fromWire),
                        TaskState.fromWire(step.getValue("to").jsonPrimitive.content),
                        step.getValue("at").jsonPrimitive.content,
                        step.getValue("attempt").jsonPrimitive.int,
                        step.optionalText("sessionId"),
                        step.optionalText("reason")
                    )
                }
                Task(
                    id = item.getValue("id").jsonPrimitive.content,
                    requestTemplate = item.getValue("requestTemplate").jsonPrimitive.content,
                    dependencies = item.getValue("dependencies").jsonArray.map { it.jsonPrimitive.content },
                    priority = item.getValue("priority").jsonPrimitive.int,
                    state = TaskState.fromWire(item.getValue("state").jsonPrimitive.content),
                    attempt = item.getValue("attempt").jsonPrimitive.int,
                    sessionId = item.optionalText("sessionId"),
                    lastResultStatus = item.optionalText("lastResultStatus"),
                    reason = item.optionalText("reason"),
                    queueOrder = item.getValue("queueOrder").jsonPrimitive.long,
                    waitTurns = item.getValue("waitTurns").jsonPrimitive.long,
                    preserveOrderOnResume = item["preserveOrderOnResume"]?.jsonPrimitive?.boolean ?: false,
                    prunedTransitions = item["prunedTransitions"]?.jsonPrimitive?.long ?: 0,
                    transitions = transitions
                )
            }
            require(tasks.map { it.id }.toSet().size == tasks.size)
            require(tasks.all { safeTaskId.matches(it.id) && it.priority in 0..100 &&
                it.attempt >= 0 && it.waitTurns >= 0 && it.queueOrder >= 0 &&
                it.prunedTransitions >= 0 })
            validateGraph(tasks)
            TaskDatabase(
                paused = obj.getValue("paused").jsonPrimitive.boolean,
                nextOrder = obj.getValue("nextOrder").jsonPrimitive.long,
                dispatches = obj["dispatches"]?.jsonPrimitive?.long ?: 0,
                tasks = tasks.toMutableList()
            )
        } catch (e: Exception) {
            throw IllegalStateException("Corrupt task store; refusing to overwrite it", e)
        }
    }

    private fun save(database: TaskDatabase) {
        require(!Files.isSymbolicLink(temp)) { "Unsafe temporary task store path" }
        val payload = buildJsonObject {
            put("version", 1); put("paused", database.paused); put("nextOrder", database.nextOrder)
            put("dispatches", database.dispatches)
            put("tasks", buildJsonArray {
                database.tasks.forEach { task ->
                    add(buildJsonObject {
                        put("id", task.id); put("requestTemplate", task.requestTemplate)
                        put("dependencies", buildJsonArray { task.dependencies.forEach { add(it) } })
                        put("priority", task.priority); put("state", task.state.wire)
                        put("attempt", task.attempt)
                        put("sessionId", task.sessionId?.let(::JsonPrimitive) ?: JsonNull)
                        put("lastResultStatus", task.lastResultStatus?.let(::JsonPrimitive) ?: JsonNull)
                        put("reason", task.reason?.let(::JsonPrimitive) ?: JsonNull)
                        put("queueOrder", task.queueOrder); put("waitTurns", task.waitTurns)
                        put("preserveOrderOnResume", task.preserveOrderOnResume)
                        put("prunedTransitions", task.prunedTransitions)
                        put("transitions", buildJsonArray {
                            task.transitions.forEach { step ->
                                add(buildJsonObject {
                                    put("from", step.from?.wire?.let(::JsonPrimitive) ?: JsonNull)
                                    put("to", step.to.wire); put("at", step.occurredAt)
                                    put("attempt", step.attempt)
                                    put("sessionId", step.sessionId?.let(::JsonPrimitive) ?: JsonNull)
                                    put("reason", step.reason?.let(::JsonPrimitive) ?: JsonNull)
                                })
                            }
                        })
                    })
                }
            })
        }.toString().toByteArray(StandardCharsets.UTF_8)
        FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(payload)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        Files.move(temp, snapshot, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        // Directory metadata fsync is supported on Unix; the snapshot remains atomic elsewhere.
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: java.io.IOException) { }
    }

    private companion object {
        val localLocks = ConcurrentHashMap<Path, ReentrantLock>()
    }
}

private val safeTaskId = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
private fun JsonObject.optionalText(field: String): String? =
    this[field]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content

/** Iterative DFS avoids stack overflows for large dependency chains. */
internal fun validateGraph(tasks: List<Task>): List<String> {
    val byId = tasks.associateBy { it.id }
    require(byId.size == tasks.size) { "Duplicate task ID" }
    val marks = mutableMapOf<String, Int>()
    val order = ArrayList<String>(tasks.size)
    // An explicit frame stores the next dependency to visit.
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

/** Single service boundary for both the CLI and future API. */
class TaskService(val store: TaskStore, private val clock: Clock = Clock.systemUTC()) {
    fun list(): List<Task> = store.transaction(write = false) { it.tasks.toList() }
    fun show(id: String): Task = store.transaction(write = false) { db -> db.find(id) }
    fun isPaused(): Boolean = store.transaction(write = false) { it.paused }

    fun enqueue(requestJson: String, priority: Int = 50, dependencies: List<String> = emptyList()): Task {
        require(requestJson.toByteArray(StandardCharsets.UTF_8).size <= 262144) { "Request too large" }
        val request = SessionRequest.parse(requestJson)
        val id = request.task.getValue("taskId").jsonPrimitive.content
        require(safeTaskId.matches(id)) { "Unsafe task ID" }
        require(priority in 0..100) { "Priority must be 0..100" }
        return store.transaction { db ->
            require(db.tasks.none { it.id == id }) { "Task already exists" }
            require(dependencies.distinct().size == dependencies.size) { "Duplicate dependency" }
            require(dependencies.all { dep -> db.tasks.any { it.id == dep } }) { "Unknown dependency" }
            val initial = Task(
                id = id, requestTemplate = request.raw.toString(), dependencies = dependencies,
                priority = priority, queueOrder = ++db.nextOrder,
                transitions = listOf(TaskTransition(null, TaskState.CREATED,
                    clock.instant().toString(), 0, null, "enqueued"))
            )
            db.tasks.add(initial)
            validateGraph(db.tasks)
            reconcile(db)
            db.find(id)
        }
    }

    /** An explicit graph-edit seam; only tasks not yet attempted can change their prerequisites. */
    fun replaceDependencies(id: String, dependencies: List<String>): Task = store.transaction { db ->
        val index = db.index(id)
        require(db.tasks[index].attempt == 0) { "Cannot edit dependencies after execution" }
        db.tasks[index] = db.tasks[index].copy(dependencies = dependencies)
        validateGraph(db.tasks)
        reconcile(db)
        db.find(id)
    }

    fun pause(): Unit = store.transaction { db -> db.paused = true; reconcile(db) }
    fun resume(): Unit = store.transaction { db -> db.paused = false; reconcile(db) }

    fun cancel(id: String): Task = store.transaction { db ->
        val index = db.index(id)
        if (db.tasks[index].state !in setOf(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED)) {
            transition(db, index, TaskState.CANCELLED, "cancelled by user")
            reconcile(db)
        }
        db.find(id)
    }

    fun cancelAll(): Unit = store.transaction { db ->
        db.paused = true
        db.tasks.indices.forEach { index ->
            if (db.tasks[index].state !in setOf(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED))
                transition(db, index, TaskState.CANCELLED, "global cancellation")
        }
        reconcile(db)
    }

    fun retry(id: String): Task = store.transaction { db ->
        val index = db.index(id)
        require(db.tasks[index].state in setOf(TaskState.FAILED, TaskState.CANCELLED,
            TaskState.NEEDS_ATTENTION)) { "Only terminal/attention tasks can be retried" }
        require(db.tasks[index].state != TaskState.NEEDS_ATTENTION ||
            db.tasks[index].reason?.startsWith("dependency:") != true) {
            "Retry the failed prerequisite first; this task is blocked by a dependency"
        }
        // Previous attempt/session remain in history. A retry receives a fresh session ID.
        db.tasks[index] = db.tasks[index].copy(sessionId = null)
        transition(db, index, TaskState.BLOCKED, "manual retry")
        reconcile(db)
        db.find(id)
    }

    internal fun claim(excludedTaskIds: Set<String> = emptySet()): Task? = store.transaction { db ->
        reconcile(db)
        if (db.paused) return@transaction null
        val eligible = db.tasks.withIndex().filter {
            it.value.state == TaskState.READY && it.value.id !in excludedTaskIds
        }
        val selected = if (db.dispatches % 8L == 7L) {
            eligible.minByOrNull { it.value.queueOrder }
        } else {
            eligible.maxWithOrNull(compareBy<IndexedValue<Task>> {
                it.value.priority.toLong() + (it.value.waitTurns.coerceAtMost(1000L) * 10L)
            }.thenBy { -it.value.queueOrder })
        } ?: return@transaction null
        db.dispatches++
        db.tasks.indices.forEach { index ->
            if (index != selected.index && db.tasks[index].state == TaskState.READY)
                db.tasks[index] = db.tasks[index].copy(waitTurns =
                    (db.tasks[index].waitTurns + 1).coerceAtMost(1000L))
        }
        val current = db.tasks[selected.index]
        val attempt = current.attempt + 1
        db.tasks[selected.index] = current.copy(attempt = attempt,
            sessionId = "session-" + current.id + "-" + attempt)
        transition(db, selected.index, TaskState.RUNNING, "claimed by local scheduler")
        db.tasks[selected.index]
    }

    internal fun finish(id: String, sessionId: String, resultStatus: String): Task = store.transaction { db ->
        val index = db.index(id)
        val current = db.tasks[index]
        if (current.state == TaskState.RUNNING && current.sessionId == sessionId) {
            val target = when (resultStatus) {
                "succeeded" -> TaskState.SUCCEEDED
                "cancelled" -> TaskState.CANCELLED
                else -> TaskState.FAILED
            }
            db.tasks[index] = current.copy(lastResultStatus = resultStatus)
            transition(db, index, target, if (target == TaskState.SUCCEEDED) null else resultStatus)
            reconcile(db)
        }
        db.find(id)
    }

    /** Stop/recovery of a known attempt is not a user-requested task cancellation. */
    internal fun interruptAttempt(id: String, sessionId: String, reason: String): Task =
        store.transaction { db ->
            val index = db.index(id)
            val task = db.tasks[index]
            if (task.state == TaskState.RUNNING && task.sessionId == sessionId) {
                transition(db, index, TaskState.NEEDS_ATTENTION, reason)
                reconcile(db)
            }
            db.find(id)
        }

    /** Called only with the exclusive process-wide scheduler lock held. Never silently replay effects. */
    internal fun recoverInterrupted() = store.transaction { db ->
        db.tasks.indices.forEach { index ->
            if (db.tasks[index].state == TaskState.RUNNING)
                transition(db, index, TaskState.NEEDS_ATTENTION, "interrupted: reconcile before retry")
        }
        reconcile(db)
    }

    internal fun requestFor(task: Task): SessionRequest {
        require(task.state == TaskState.RUNNING && task.sessionId != null)
        val source = Json.parseToJsonElement(task.requestTemplate).jsonObject
        val runRoot = store.directory.resolve("runs").resolve(task.id)
            .resolve("attempt-" + task.attempt).toAbsolutePath()
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

    private fun reconcile(db: TaskDatabase) {
        val order = validateGraph(db.tasks)
        val indexById = db.tasks.indices.associateBy { db.tasks[it].id }
        for (id in order) {
            val index = indexById.getValue(id)
            val task = db.tasks[index]
            if (task.state in setOf(TaskState.RUNNING, TaskState.SUCCEEDED,
                    TaskState.FAILED, TaskState.CANCELLED)) continue
            if (task.state == TaskState.NEEDS_ATTENTION &&
                task.reason?.startsWith("dependency:") != true) continue
            val prerequisites = task.dependencies.map { db.tasks[indexById.getValue(it)] }
            val missing = prerequisites.firstOrNull {
                it.state in setOf(TaskState.FAILED, TaskState.CANCELLED, TaskState.NEEDS_ATTENTION)
            }
            val next = when {
                missing != null -> TaskState.NEEDS_ATTENTION
                db.paused -> TaskState.PAUSED
                prerequisites.all { it.state == TaskState.SUCCEEDED } -> TaskState.READY
                else -> TaskState.BLOCKED
            }
            val reason = if (missing == null) null else "dependency:" + missing.id + ":" + missing.state.wire
            transition(db, index, next, reason)
        }
    }

    private fun transition(db: TaskDatabase, index: Int, state: TaskState, reason: String?) {
        val current = db.tasks[index]
        if (current.state == state && current.reason == reason) return
        val becameReady = state == TaskState.READY && current.state != TaskState.READY &&
            (current.state != TaskState.PAUSED || !current.preserveOrderOnResume)
        val events = current.transitions + TaskTransition(current.state, state,
            clock.instant().toString(), current.attempt, current.sessionId, reason)
        val removed = (events.size - MAX_TRANSITIONS).coerceAtLeast(0)
        db.tasks[index] = current.copy(
            state = state, reason = reason,
            queueOrder = if (becameReady) ++db.nextOrder else current.queueOrder,
            waitTurns = if (becameReady) 0 else current.waitTurns,
            preserveOrderOnResume = when (state) {
                TaskState.PAUSED -> current.state == TaskState.READY
                else -> false
            },
            prunedTransitions = current.prunedTransitions + removed,
            transitions = events.drop(removed)
        )
    }

    private companion object {
        const val MAX_TRANSITIONS = 128
    }

    private fun TaskDatabase.index(id: String): Int =
        tasks.indexOfFirst { it.id == id }.also { require(it >= 0) { "Unknown task" } }
    private fun TaskDatabase.find(id: String): Task = tasks[index(id)]
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
        val lockPath = service.store.directory.resolve(".scheduler.lock")
        require(!Files.isSymbolicLink(lockPath))
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            check(lock != null) { "Another scheduler is active" }
            lock.use {
                if (stopping.get()) return SchedulerReport(0, 0, service.isPaused())
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
                return SchedulerReport(launched, completed, service.isPaused())
            }
        }
    }
}

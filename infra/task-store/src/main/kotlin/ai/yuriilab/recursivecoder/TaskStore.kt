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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.*

/**
 * Atomic snapshot transactions guarded by both a per-JVM mutex and an OS file lock.
 * The previous snapshot is never truncated: a killed writer leaves either the old
 * or the new complete snapshot. A stray .tmp file is not recovery authority.
 */
class TaskStore(directory: Path) : TaskRepository {
    val directory: Path = directory.toAbsolutePath().normalize()
    private val snapshot = this.directory.resolve("tasks.json")
    private val temp = this.directory.resolve("tasks.json.tmp")
    private val guardFile = this.directory.resolve(".store.lock")

    init {
        require(!Files.isSymbolicLink(this.directory)) { "Task store cannot be a symlink" }
        Files.createDirectories(this.directory)
        require(Files.isDirectory(this.directory, LinkOption.NOFOLLOW_LINKS))
    }

    /** Read and mutation callbacks execute under the SAME cross-process OS lock. */
    private fun <T> transaction(operation: (TaskQueue) -> T): T {
        val mutex = localLocks.computeIfAbsent(directory) { ReentrantLock() }
        return mutex.withLock {
            require(!Files.isSymbolicLink(guardFile) && !Files.isSymbolicLink(snapshot))
            FileChannel.open(guardFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { operation(load()) }
            }
        }
    }

    override fun <T> read(operation: (TaskQueue) -> T): T = transaction(operation)

    override fun <T> update(operation: (TaskQueue) -> TaskMutation<T>): T =
        transaction { current ->
            val change = operation(current)
            if (change.next != current) save(change.next)
            change.value
        }

    /** This lock MUST span recovery, dispatch, and worker shutdown, not one claim. */
    override fun <T> withSchedulerLock(operation: () -> T): T {
        val lockPath = directory.resolve(".scheduler.lock")
        require(!Files.isSymbolicLink(lockPath))
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            check(lock != null) { "Another scheduler is active" }
            lock.use { return operation() }
        }
    }

    override fun outputRoot(task: Task): Path = directory.resolve("runs").resolve(task.id)
        .resolve("attempt-" + task.attempt).toAbsolutePath()

    private fun load(): TaskQueue {
        if (!Files.exists(snapshot, LinkOption.NOFOLLOW_LINKS)) return TaskQueue()
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
                    lastResultStatus = item.optionalText("lastResultStatus")?.let(SessionOutcome::fromWire),
                    reason = item.optionalText("reason"),
                    queueOrder = item.getValue("queueOrder").jsonPrimitive.long,
                    waitTurns = item.getValue("waitTurns").jsonPrimitive.long,
                    preserveOrderOnResume = item["preserveOrderOnResume"]?.jsonPrimitive?.boolean ?: false,
                    prunedTransitions = item["prunedTransitions"]?.jsonPrimitive?.long ?: 0,
                    transitions = transitions
                )
            }
            validateStoredTasks(tasks)
            TaskQueue(
                paused = obj.getValue("paused").jsonPrimitive.boolean,
                nextOrder = obj.getValue("nextOrder").jsonPrimitive.long,
                dispatches = obj["dispatches"]?.jsonPrimitive?.long ?: 0,
                tasks = tasks
            )
        } catch (e: Exception) {
            throw IllegalStateException("Corrupt task store; refusing to overwrite it", e)
        }
    }

    private fun save(database: TaskQueue) {
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
                        put("lastResultStatus", task.lastResultStatus?.wire?.let(::JsonPrimitive) ?: JsonNull)
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

private fun JsonObject.optionalText(field: String): String? =
    this[field]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content

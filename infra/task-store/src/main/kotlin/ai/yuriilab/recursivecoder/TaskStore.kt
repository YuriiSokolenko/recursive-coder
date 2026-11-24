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
    // Set only while this thread owns the real .scheduler.lock OS file lock.
    private val schedulerLeaseHeld = ThreadLocal.withInitial { false }

    internal val testHooks = TaskStoreTestHooks()

    init {
        require(!Files.isSymbolicLink(this.directory)) { "Task store cannot be a symlink" }
        Files.createDirectories(this.directory)
        require(Files.isDirectory(this.directory, LinkOption.NOFOLLOW_LINKS))
    }

    /** Read and mutation callbacks execute under the SAME cross-process OS lock. */
    private fun <T> transaction(operation: (TaskQueue) -> T): T {
        // A symlinked parent may expose the same .store.lock via different
        // normalized paths. Serialize those aliases BEFORE opening a channel:
        // closing a failed second channel can drop the first POSIX file lock.
        val mutex = localLocks.computeIfAbsent(directory.toRealPath()) { ReentrantLock() }
        check(!mutex.isHeldByCurrentThread) { "Nested task store transaction is not allowed" }
        return mutex.withLock {
            require(!Files.isSymbolicLink(guardFile) && !Files.isSymbolicLink(snapshot))
            FileChannel.open(guardFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                testHooks.beforeStoreLockAcquisition?.invoke()
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
        // On POSIX systems, closing even an unsuccessful second FileChannel may
        // release this JVM's existing locks on the same file. Deny overlapping
        // TaskStore acquisitions BEFORE opening a second channel. Canonicalize
        // the directory to catch aliases through symlinked parent directories.
        // This is only an in-JVM safety guard; the OS file lock remains authoritative.
        val leaseKey = directory.toRealPath()
        check(activeSchedulerLeases.add(leaseKey)) { "Another scheduler is active" }
        try {
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                check(lock != null) { "Another scheduler is active" }
                lock.use {
                    schedulerLeaseHeld.set(true)
                    try {
                        return operation()
                    } finally {
                        schedulerLeaseHeld.remove()
                    }
                }
            }
        } finally {
            // The OS lock/channel is closed before another TaskStore may enter.
            activeSchedulerLeases.remove(leaseKey)
        }
    }

    override fun requireSchedulerLockHeld() {
        check(schedulerLeaseHeld.get()) { "Scheduler lease required for recovery" }
    }

    private fun load(): TaskQueue {
        if (!Files.exists(snapshot, LinkOption.NOFOLLOW_LINKS)) return TaskQueue()
        require(Files.size(snapshot) <= 64L * 1024 * 1024) { "Task store is too large" }
        return try {
            val obj = Json.parseToJsonElement(Files.readString(snapshot)).jsonObject
            require(obj.getValue(TaskStoreFields.VERSION).jsonPrimitive.int == TASK_STORE_VERSION)
            val tasks = obj.getValue(TaskStoreFields.TASKS).jsonArray.map { element ->
                val item = element.jsonObject
                val transitions = item.getValue(TaskStoreFields.TRANSITIONS).jsonArray.map { event ->
                    val step = event.jsonObject
                    TaskTransition(
                        step.optionalText(TaskStoreFields.FROM)?.let(TaskState::fromWire),
                        TaskState.fromWire(step.getValue(TaskStoreFields.TO).jsonPrimitive.content),
                        step.getValue(TaskStoreFields.AT).jsonPrimitive.content,
                        step.getValue(TaskStoreFields.ATTEMPT).jsonPrimitive.int,
                        step.optionalText(TaskStoreFields.SESSION_ID),
                        step.optionalText(TaskStoreFields.REASON)
                    )
                }
                Task(
                    id = item.getValue(TaskStoreFields.ID).jsonPrimitive.content,
                    requestTemplate = item.getValue(TaskStoreFields.REQUEST_TEMPLATE).jsonPrimitive.content,
                    dependencies = item.getValue(TaskStoreFields.DEPENDENCIES).jsonArray.map { it.jsonPrimitive.content },
                    priority = item.getValue(TaskStoreFields.PRIORITY).jsonPrimitive.int,
                    state = TaskState.fromWire(item.getValue(TaskStoreFields.STATE).jsonPrimitive.content),
                    attempt = item.getValue(TaskStoreFields.ATTEMPT).jsonPrimitive.int,
                    sessionId = item.optionalText(TaskStoreFields.SESSION_ID),
                    lastResultStatus = item.optionalText(TaskStoreFields.LAST_RESULT_STATUS)?.let(SessionOutcome::fromWire),
                    reason = item.optionalText(TaskStoreFields.REASON),
                    queueOrder = item.getValue(TaskStoreFields.QUEUE_ORDER).jsonPrimitive.long,
                    waitTurns = item.getValue(TaskStoreFields.WAIT_TURNS).jsonPrimitive.long,
                    preserveOrderOnResume = item[TaskStoreFields.PRESERVE_ORDER_ON_RESUME]?.jsonPrimitive?.boolean ?: false,
                    prunedTransitions = item[TaskStoreFields.PRUNED_TRANSITIONS]?.jsonPrimitive?.long ?: 0,
                    transitions = transitions
                )
            }
            validateStoredTasks(tasks)
            TaskQueue(
                paused = obj.getValue(TaskStoreFields.PAUSED).jsonPrimitive.boolean,
                nextOrder = obj.getValue(TaskStoreFields.NEXT_ORDER).jsonPrimitive.long,
                dispatches = obj[TaskStoreFields.DISPATCHES]?.jsonPrimitive?.long ?: 0,
                tasks = tasks
            )
        } catch (e: Exception) {
            throw IllegalStateException("Corrupt task store; refusing to overwrite it", e)
        }
    }

    private fun save(database: TaskQueue) {
        require(!Files.isSymbolicLink(temp)) { "Unsafe temporary task store path" }
        val payload = buildJsonObject {
            put(TaskStoreFields.VERSION, TASK_STORE_VERSION); put(TaskStoreFields.PAUSED, database.paused); put(TaskStoreFields.NEXT_ORDER, database.nextOrder)
            put(TaskStoreFields.DISPATCHES, database.dispatches)
            put(TaskStoreFields.TASKS, buildJsonArray {
                database.tasks.forEach { task ->
                    add(buildJsonObject {
                        put(TaskStoreFields.ID, task.id); put(TaskStoreFields.REQUEST_TEMPLATE, task.requestTemplate)
                        put(TaskStoreFields.DEPENDENCIES, buildJsonArray { task.dependencies.forEach { add(it) } })
                        put(TaskStoreFields.PRIORITY, task.priority); put(TaskStoreFields.STATE, task.state.wire)
                        put(TaskStoreFields.ATTEMPT, task.attempt)
                        put(TaskStoreFields.SESSION_ID, task.sessionId?.let(::JsonPrimitive) ?: JsonNull)
                        put(TaskStoreFields.LAST_RESULT_STATUS, task.lastResultStatus?.wire?.let(::JsonPrimitive) ?: JsonNull)
                        put(TaskStoreFields.REASON, task.reason?.let(::JsonPrimitive) ?: JsonNull)
                        put(TaskStoreFields.QUEUE_ORDER, task.queueOrder); put(TaskStoreFields.WAIT_TURNS, task.waitTurns)
                        put(TaskStoreFields.PRESERVE_ORDER_ON_RESUME, task.preserveOrderOnResume)
                        put(TaskStoreFields.PRUNED_TRANSITIONS, task.prunedTransitions)
                        put(TaskStoreFields.TRANSITIONS, buildJsonArray {
                            task.transitions.forEach { step ->
                                add(buildJsonObject {
                                    put(TaskStoreFields.FROM, step.from?.wire?.let(::JsonPrimitive) ?: JsonNull)
                                    put(TaskStoreFields.TO, step.to.wire); put(TaskStoreFields.AT, step.occurredAt)
                                    put(TaskStoreFields.ATTEMPT, step.attempt)
                                    put(TaskStoreFields.SESSION_ID, step.sessionId?.let(::JsonPrimitive) ?: JsonNull)
                                    put(TaskStoreFields.REASON, step.reason?.let(::JsonPrimitive) ?: JsonNull)
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
        testHooks.atomicReplaceFailure?.invoke(temp, snapshot)
        Files.move(temp, snapshot, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        // Directory metadata fsync makes the rename durable. On Linux a failure
        // means a committed claim/finish could be lost after power loss, so it is
        // never ignored there (issue #162). Other platforms may not support opening
        // a directory channel; the rename itself remains atomic.
        try {
            testHooks.directorySync?.invoke(directory)
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (failure: java.io.IOException) {
            if (System.getProperty("os.name").startsWith("Linux"))
                throw java.io.IOException("Task snapshot is not durable", failure)
        }
    }

    private companion object {
        val localLocks = ConcurrentHashMap<Path, ReentrantLock>()
        // Shared by all TaskStore instances; never used in place of .scheduler.lock.
        val activeSchedulerLeases = ConcurrentHashMap.newKeySet<Path>()
    }
}

/** Instance-local test seams; all callbacks are absent in production. */
internal class TaskStoreTestHooks {
    /** #162: immediately before post-rename directory fsync. */
    @Volatile var directorySync: ((Path) -> Unit)? = null

    /** Immediately before the real blocking OS lock. */
    @Volatile var beforeStoreLockAcquisition: (() -> Unit)? = null

    /** After temp force and close; Nothing prevents bypassing the real move. */
    @Volatile var atomicReplaceFailure: ((Path, Path) -> Nothing)? = null
}

private fun JsonObject.optionalText(field: String): String? =
    this[field]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content

private const val TASK_STORE_VERSION = 1

/** Private persistence fields, not the public task CLI contract. */
/** Durable tasks.json v1 keys; independent of the CLI projection. */
private object TaskStoreFields {
    const val STATE = "state"
    const val PRIORITY = "priority"
    const val DEPENDENCIES = "dependencies"
    const val ATTEMPT = "attempt"
    const val PRUNED_TRANSITIONS = "prunedTransitions"
    const val SESSION_ID = "sessionId"
    const val LAST_RESULT_STATUS = "lastResultStatus"
    const val REASON = "reason"
    const val TRANSITIONS = "transitions"
    const val FROM = "from"
    const val TO = "to"
    const val AT = "at"
    const val VERSION = "version"
    const val PAUSED = "paused"
    const val NEXT_ORDER = "nextOrder"
    const val DISPATCHES = "dispatches"
    const val TASKS = "tasks"
    const val ID = "id"
    const val REQUEST_TEMPLATE = "requestTemplate"
    const val QUEUE_ORDER = "queueOrder"
    const val WAIT_TURNS = "waitTurns"
    const val PRESERVE_ORDER_ON_RESUME = "preserveOrderOnResume"
}

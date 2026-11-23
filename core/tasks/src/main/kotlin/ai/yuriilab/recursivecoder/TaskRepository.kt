package ai.yuriilab.recursivecoder


/** Atomic read-decide-write result; the returned value is committed with [next]. */
data class TaskMutation<T>(val next: TaskQueue, val value: T)

/**
 * Durable queue boundary. update MUST re-read the latest snapshot and atomically commit
 * the returned snapshot while holding a lock that excludes other processes. Exceptions
 * abort the write. read is equally serialized and must reject corrupt snapshots.
 * read/update callbacks must not re-enter this repository (including nested read/update).
 *
 * withSchedulerLock excludes another scheduler for the complete lifecycle, including
 * interrupted-attempt recovery and all worker shutdown. The recovery Use Case
 * checks requireSchedulerLockHeld before any mutation. Implementations must
 * derive ownership from the successfully acquired scheduler lease on the current
 * thread; a local marker alone must never replace process-wide exclusion.
 * TaskQueue and TaskMutation are public only because adapter implementations
 * are separate Gradle modules;
 * they are not external application state-management APIs.
 */
interface TaskRepository {
    fun <T> read(operation: (TaskQueue) -> T): T
    fun <T> update(operation: (TaskQueue) -> TaskMutation<T>): T
    fun <T> withSchedulerLock(operation: () -> T): T

    /** Fail fast unless this thread currently holds this repository's scheduler lease. */
    fun requireSchedulerLockHeld()

}

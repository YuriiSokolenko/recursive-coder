package ai.yuriilab.recursivecoder

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Memory-only adapter for Task policy and use-case tests; no files are opened. */
class InMemoryTaskRepository : TaskRepository {
    private val dataLock = ReentrantLock()
    private val schedulerLock = ReentrantLock()
    private var current = TaskQueue()

    override fun <T> read(operation: (TaskQueue) -> T): T =
        dataLock.withLock { operation(current) }

    override fun <T> update(operation: (TaskQueue) -> TaskMutation<T>): T =
        dataLock.withLock {
            val change = operation(current)
            current = change.next
            change.value
        }

    override fun <T> withSchedulerLock(operation: () -> T): T {
        check(!schedulerLock.isHeldByCurrentThread && schedulerLock.tryLock()) {
            "Another scheduler is active"
        }
        try {
            return operation()
        } finally {
            schedulerLock.unlock()
        }
    }

    override fun requireSchedulerLockHeld() {
        check(schedulerLock.isHeldByCurrentThread) { "Scheduler lease required for recovery" }
    }
}

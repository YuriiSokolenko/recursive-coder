package ai.yuriilab.recursivecoder

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Deadline scheduling is a real elapsed-time operation even if the injected
 * session Clock is fixed. Tests may replace this port to fire expiry manually;
 * the core still checks the injected clock's effective deadline at each gate.
 */
fun interface SessionDeadlineScheduler {
    fun schedule(delayMs: Long, onDeadline: () -> Unit): AutoCloseable
}

/** Production: one daemon executor per invocation, cancelled on session exit. */
internal object RealTimeSessionDeadlineScheduler : SessionDeadlineScheduler {
    override fun schedule(delayMs: Long, onDeadline: () -> Unit): AutoCloseable {
        val executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "session-deadline").apply { isDaemon = true }
        }
        try {
            executor.schedule({ onDeadline() }, delayMs, TimeUnit.MILLISECONDS)
        } catch (failure: Throwable) {
            executor.shutdownNow()
            throw failure
        }
        return AutoCloseable { executor.shutdownNow() }
    }
}


package ai.yuriilab.recursivecoder

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class CancellationUnitTest {
    @Test fun lateCancellationAndExpiryCannotInterruptUnboundWorker() {
        val cancellation = Cancellation()
        val unbound = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = AtomicBoolean(true)
        val worker = thread(name = "unbound-cancellation-worker") {
            cancellation.bind()
            cancellation.unbind()
            unbound.countDown()
            try {
                release.await(5, TimeUnit.SECONDS)
                interrupted.set(Thread.currentThread().isInterrupted)
            } finally {
                release.countDown()
            }
        }
        try {
            assertTrue(unbound.await(5, TimeUnit.SECONDS))
            repeat(20) {
                cancellation.cancel()
                cancellation.expire()
            }
            assertEquals(CancellationSignals(requested = true, expired = true),
                cancellation.signals())
            release.countDown()
            worker.join(5000)
            assertFalse(worker.isAlive)
            assertFalse(interrupted.get(), "Unbound worker must not be interrupted")
        } finally {
            release.countDown()
            worker.join(5000)
        }
    }

    @Test fun preBindCancellationInterruptsAndRepeatedCancelDoesNotReinterrupt() {
        val cancellation = Cancellation()
        cancellation.cancel()
        val interruptedAtBind = AtomicBoolean(false)
        val repeatedCancelInterrupted = AtomicBoolean(true)
        val worker = thread(name = "prebind-cancellation-worker") {
            cancellation.bind()
            interruptedAtBind.set(Thread.interrupted())
            cancellation.cancel()
            repeatedCancelInterrupted.set(Thread.interrupted())
            cancellation.unbind()
        }
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertTrue(interruptedAtBind.get(), "Pre-bind cancellation must reach the worker")
        assertFalse(repeatedCancelInterrupted.get(), "Repeated cancel must be idempotent")
    }
}

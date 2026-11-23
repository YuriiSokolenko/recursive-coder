package ai.yuriilab.recursivecoder

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class CancellationUnitTest {
    @Test fun cancellationClockAffinityAcceptsEquivalentClocksAndRejectsDifferentClocks() {
        val time = Instant.parse("2045-02-02T10:00:00Z")
        val fixed = Clock.fixed(time, ZoneOffset.UTC)
        val equivalent = Clock.fixed(time, ZoneOffset.UTC)
        val different = Clock.fixed(time.plusSeconds(1), ZoneOffset.UTC)

        assertTrue(Cancellation(fixed).usesClock(equivalent))
        assertFalse(Cancellation(fixed).usesClock(different))
        assertTrue(Cancellation().usesClock(Clock.systemUTC()))
    }

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

    @Test fun preBindExpiryInterruptsAndRepeatedExpireDoesNotReinterrupt() {
        val cancellation = Cancellation()
        cancellation.expire()
        val interruptedAtBind = AtomicBoolean(false)
        val repeatedExpireInterrupted = AtomicBoolean(true)
        val worker = thread(name = "prebind-expiry-worker") {
            cancellation.bind()
            interruptedAtBind.set(Thread.interrupted())
            cancellation.expire()
            repeatedExpireInterrupted.set(Thread.interrupted())
            cancellation.unbind()
        }
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertTrue(interruptedAtBind.get(), "Pre-bind expiry must reach the worker")
        assertFalse(repeatedExpireInterrupted.get(), "Repeated expiry must be idempotent")
    }

    @Test fun unbindFromAnotherThreadCannotInterruptPreviousWorker() {
        val cancellation = Cancellation()
        val bound = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = AtomicBoolean(true)
        val worker = thread(name = "cross-thread-unbind-worker") {
            cancellation.bind()
            bound.countDown()
            try {
                release.await(5, TimeUnit.SECONDS)
                interrupted.set(Thread.currentThread().isInterrupted)
            } finally {
                release.countDown()
            }
        }
        try {
            assertTrue(bound.await(5, TimeUnit.SECONDS))
            cancellation.unbind()
            cancellation.cancel()
            release.countDown()
            worker.join(5000)
            assertFalse(worker.isAlive)
            assertFalse(interrupted.get(), "Unbind must stop later interrupts from this token")
        } finally {
            release.countDown()
            worker.join(5000)
        }
    }
}

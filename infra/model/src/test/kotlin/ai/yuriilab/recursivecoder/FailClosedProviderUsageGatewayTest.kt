package ai.yuriilab.recursivecoder

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Recorded provider-adapter results; no network or live writable workflow. */
@Tag("integration")
class FailClosedProviderUsageGatewayTest {
    private val start = Instant.parse("2035-01-01T00:00:00Z")
    private val now = AtomicReference(start)
    private val clock: Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now.get()
    }
    private val deadline = start.plusSeconds(60)
    private val config = ModelConfig(
        "openai-compatible", "demo", "http://127.0.0.1:4001/v1",
        "env:UNSET_TEST_CREDENTIAL", 4096, 120,
        setOf("tool_calls", "usage_metadata"), null, 1000, 1000
    )
    private val task = Path.of("/safe/task.md")

    private class RecordedGateway(
        private val response: (ModelConfig, Int) -> ModelTurn
    ) : ModelGateway {
        var calls = 0
        val caps = mutableListOf<Long>()
        val retries = mutableListOf<Boolean>()
        val discoveryTimeouts = mutableListOf<Long>()
        override fun negotiate(config: ModelConfig, cancellation: Cancellation,
            deadline: Instant): GatewayCapabilities {
            discoveryTimeouts.add(config.requestTimeoutMs)
            return GatewayCapabilities(config.features,
                config.contextTokens, config.maxOutputTokens)
        }
        override fun next(config: ModelConfig, task: Path,
            observed: Pair<Int, String>?, cancellation: Cancellation,
            deadline: Instant, allowRetry: Boolean): ModelTurn {
            calls++
            caps.add(config.maxOutputTokens)
            retries.add(allowRetry)
            return response(config, calls)
        }
    }

    private fun reply(input: Long = 10, output: Long = 5,
        attempts: Int = 1, status: Int? = 200) =
        ModelTurn(ModelDecision.Refusal, ModelUsage(input, output),
            attempts, status)

    private fun meter(gateway: ModelGateway, grant: Long = 50) =
        FailClosedProviderUsageGateway(gateway, grant, deadline, clock)

    private fun FailClosedProviderUsageGateway.next(allowRetry: Boolean = true,
        model: ModelConfig = config) =
        next(model, task, null, Cancellation(), deadline, allowRetry)

    private fun failureCode(block: () -> Unit): String =
        assertFailsWith<Refused> { block() }.code

    @Test fun cumulativeUsageAndOutputCapsAreMeasuredAcrossTurns() {
        val delegate = RecordedGateway { _, _ -> reply(10, 5) }
        val ledger = meter(delegate, 30)
        ledger.next()
        ledger.next()
        assertEquals("MODEL_TOKEN_BUDGET_EXHAUSTED",
            failureCode { ledger.next() })
        assertEquals(listOf(30L, 15L), delegate.caps)
        assertEquals(listOf(false, false), delegate.retries)
        assertEquals(2, delegate.calls)
        val snapshot = ledger.snapshot()
        assertEquals(20L, snapshot.observedInputTokens)
        assertEquals(10L, snapshot.observedOutputTokens)
        assertEquals(30L, snapshot.accountedTokens)
        assertEquals(2, snapshot.receipts.size)
        assertTrue(snapshot.receipts.all { it.provenance == "host-adapter-response" })
        assertFalse(snapshot.usageUnconfirmed)
    }

    @Test fun overrunKeepsObservedConsumptionButCannotAuthorizeAnotherTurn() {
        val delegate = RecordedGateway { _, index ->
            if (index == 1) reply(20, 5) else reply(20, 6)
        }
        val ledger = meter(delegate, 40)
        ledger.next()
        assertEquals("MODEL_TOKEN_BUDGET_EXHAUSTED",
            failureCode { ledger.next() })
        assertEquals("MODEL_TOKEN_BUDGET_EXHAUSTED",
            failureCode { ledger.next() })
        val snapshot = ledger.snapshot()
        assertEquals(51L, snapshot.accountedTokens)
        assertEquals(51L, snapshot.observedInputTokens + snapshot.observedOutputTokens)
        assertEquals(2, snapshot.receipts.size)
        assertEquals(FailClosedProviderUsageGateway.ReceiptOutcome.BUDGET_EXCEEDED,
            snapshot.receipts.last().outcome)
        assertFalse(snapshot.usageUnconfirmed)
        assertEquals(2, delegate.calls)
        assertEquals(listOf(40L, 15L), delegate.caps)
    }

    @Test fun missingUsagePoisonsTheMeterAndBlocksAllSubsequentCalls() {
        val delegate = RecordedGateway { _, _ ->
            ModelTurn(ModelDecision.Refusal, null, 1, 200)
        }
        val ledger = meter(delegate)
        assertEquals("MODEL_USAGE_UNCONFIRMED",
            failureCode { ledger.next() })
        assertEquals("MODEL_USAGE_UNCONFIRMED",
            failureCode { ledger.next() })
        assertEquals(1, delegate.calls)
        assertEquals(50L, ledger.snapshot().accountedTokens)
        assertTrue(ledger.snapshot().usageUnconfirmed)
    }

    @Test fun multiAttemptOrUnverifiableStatusCannotBeTreatedAsOneBilledResponse() {
        for (reply in listOf(reply(attempts = 2), reply(status = null),
                reply(status = 503))) {
            val delegate = RecordedGateway { _, _ -> reply }
            val ledger = meter(delegate)
            assertEquals("MODEL_USAGE_UNCONFIRMED",
                failureCode { ledger.next() })
            assertEquals(50L, ledger.snapshot().accountedTokens)
            assertEquals(1, delegate.calls)
            assertTrue(ledger.snapshot().usageUnconfirmed)
            assertEquals(listOf(false), delegate.retries)
        }
    }

    @Test fun invalidNegativeAndOverflowedProviderCountersFailClosed() {
        for (bad in listOf(reply(-1, 5), reply(1, -1), reply(0, 0),
                reply(Long.MAX_VALUE, 1))) {
            val delegate = RecordedGateway { _, _ -> bad }
            val ledger = meter(delegate, Long.MAX_VALUE)
            assertEquals("MODEL_USAGE_UNCONFIRMED",
                failureCode { ledger.next() })
            assertTrue(ledger.snapshot().usageUnconfirmed)
        }
    }

    @Test fun cumulativeInputOverflowIsUnconfirmedNotWrappedToZero() {
        val delegate = RecordedGateway { _, index ->
            if (index == 1) reply(Long.MAX_VALUE - 2, 0) else reply(10, 0)
        }
        val ledger = meter(delegate, Long.MAX_VALUE)
        ledger.next()
        assertEquals("MODEL_USAGE_UNCONFIRMED",
            failureCode { ledger.next() })
        assertEquals(Long.MAX_VALUE, ledger.snapshot().accountedTokens)
        assertTrue(ledger.snapshot().usageUnconfirmed)
    }

    @Test fun providerExceptionAfterDispatchConsumesNonrefundableReservation() {
        val delegate = RecordedGateway { _, _ ->
            throw Refused("MODEL_TRANSPORT_FAILED", "provider", "private-provider-detail")
        }
        val ledger = meter(delegate)
        assertEquals("MODEL_USAGE_UNCONFIRMED",
            failureCode { ledger.next() })
        assertEquals(50L, ledger.snapshot().accountedTokens)
        assertTrue(ledger.snapshot().usageUnconfirmed)
        assertEquals(1, delegate.calls)
        assertEquals("MODEL_TRANSPORT_FAILED", ledger.snapshot().causeCode)
        assertEquals(FailClosedProviderUsageGateway.ReceiptOutcome.UNCONFIRMED,
            ledger.snapshot().receipts.single().outcome)
        assertEquals("MODEL_USAGE_UNCONFIRMED",
            failureCode { ledger.next() })
        assertEquals(1, delegate.calls)
    }

    @Test fun responseAfterDeadlineIsRecordedButNeverAccepted() {
        val delegate = RecordedGateway { _, _ ->
            now.set(deadline)
            reply()
        }
        val ledger = meter(delegate)
        assertEquals("MODEL_DEADLINE_EXCEEDED",
            failureCode { ledger.next() })
        assertEquals(10L, ledger.snapshot().observedInputTokens)
        assertEquals(5L, ledger.snapshot().observedOutputTokens)
        assertEquals(15L, ledger.snapshot().accountedTokens)
        assertEquals(FailClosedProviderUsageGateway.ReceiptOutcome.DEADLINE_EXCEEDED,
            ledger.snapshot().receipts.single().outcome)
        assertEquals("MODEL_DEADLINE_EXCEEDED",
            failureCode { ledger.next() })
        assertEquals(1, delegate.calls)
    }

    @Test fun unsupportedProvenanceCannotCallDelegate() {
        val delegate = RecordedGateway { _, _ -> reply() }
        val ledger = meter(delegate)
        assertEquals("MODEL_USAGE_PROVENANCE_UNAVAILABLE",
            failureCode { ledger.next(model = config.copy(features = setOf("tool_calls"))) })
        assertEquals("MODEL_USAGE_PROVENANCE_UNAVAILABLE",
            failureCode { ledger.next(model = config.copy(provider = "fake")) })
        assertEquals(0, delegate.calls)
    }

    @Test fun cancellationAfterDispatchDoesNotAcceptResponseOrPermitFurtherWork() {
        val cancellation = Cancellation()
        val delegate = RecordedGateway { _, _ ->
            cancellation.cancel()
            reply()
        }
        val ledger = meter(delegate)
        assertFailsWith<Stopped> {
            ledger.next(config, task, null, cancellation, deadline, true)
        }
        assertFalse(ledger.snapshot().usageUnconfirmed)
        assertEquals(15L, ledger.snapshot().accountedTokens)
        assertEquals(FailClosedProviderUsageGateway.ReceiptOutcome.CANCELLED,
            ledger.snapshot().receipts.single().outcome)
        assertEquals("MODEL_CANCELLED_AFTER_RESPONSE",
            failureCode { ledger.next() })
        assertEquals(1, delegate.calls)
    }

    @Test fun streamingIsNotAcceptedWithoutTerminalUsageProvenance() {
        val delegate = RecordedGateway { _, _ -> reply() }
        val ledger = meter(delegate)
        assertEquals("MODEL_USAGE_PROVENANCE_UNAVAILABLE",
            failureCode { ledger.next(model = config.copy(
                features = config.features + "streaming")) })
        assertEquals(0, delegate.calls)
    }

    @Test fun expiredGrantCannotDispatchAnyModelCall() {
        now.set(deadline)
        val delegate = RecordedGateway { _, _ -> reply() }
        val ledger = meter(delegate)
        assertEquals("MODEL_DEADLINE_EXCEEDED", failureCode { ledger.next() })
        assertEquals(0, delegate.calls)
        assertEquals(0L, ledger.snapshot().accountedTokens)
    }

    @Test fun completionIgnoringOutputCapIsDenied() {
        val delegate = RecordedGateway { _, _ -> reply(input = 1, output = 15) }
        val ledger = meter(delegate, 10)
        assertEquals("MODEL_TOKEN_BUDGET_EXHAUSTED",
            failureCode { ledger.next() })
        assertEquals(listOf(10L), delegate.caps)
        assertEquals(16L, ledger.snapshot().observedInputTokens +
            ledger.snapshot().observedOutputTokens)
        assertEquals(16L, ledger.snapshot().accountedTokens)
        assertEquals(FailClosedProviderUsageGateway.ReceiptOutcome.BUDGET_EXCEEDED,
            ledger.snapshot().receipts.single().outcome)
        assertEquals(1, delegate.calls)
    }

    @Test fun requestTimeoutIsClampedToTheAdmittedDeadline() {
        now.set(deadline.minusMillis(125))
        var receivedTimeout = -1L
        val delegate = RecordedGateway { requested, _ ->
            receivedTimeout = requested.requestTimeoutMs
            reply(input = 10, output = 5)
        }
        val ledger = meter(delegate)
        ledger.next()
        assertEquals(125L, receivedTimeout)
        assertEquals(1, delegate.calls)
    }

    @Test fun discoveryTimeoutIsClampedWithoutReservingInferenceTokens() {
        now.set(deadline.minusMillis(125))
        val delegate = RecordedGateway { _, _ -> reply() }
        val ledger = meter(delegate)
        ledger.negotiate(config, Cancellation(), deadline)
        assertEquals(listOf(125L), delegate.discoveryTimeouts)
        assertEquals(0, delegate.calls)
        assertEquals(0L, ledger.snapshot().accountedTokens)
    }

    @Test fun invalidDiscoveryTimeoutDoesNotPoisonTheMeter() {
        val delegate = RecordedGateway { _, _ -> reply() }
        val ledger = meter(delegate)
        assertEquals("MODEL_REQUEST_TIMEOUT_INVALID", failureCode {
            ledger.negotiate(config.copy(requestTimeoutMs = 0), Cancellation(), deadline)
        })
        val denied = ledger.snapshot()
        assertEquals(null, denied.terminalCode)
        assertFalse(denied.usageUnconfirmed)
        assertFalse(denied.inFlight)
        assertEquals(0L, denied.accountedTokens)
        assertTrue(denied.receipts.isEmpty())
        assertTrue(delegate.discoveryTimeouts.isEmpty())

        ledger.negotiate(config, Cancellation(), deadline)
        assertEquals(listOf(1000L), delegate.discoveryTimeouts)
        assertEquals(0, delegate.calls)
    }

    @Test fun subMillisecondDiscoveryDeadlineDoesNotPoisonTheMeter() {
        val delegate = RecordedGateway { _, _ -> reply() }
        val ledger = meter(delegate)
        assertEquals("MODEL_DEADLINE_EXCEEDED", failureCode {
            ledger.negotiate(config, Cancellation(), start.plusNanos(500_000))
        })
        val denied = ledger.snapshot()
        assertEquals(null, denied.terminalCode)
        assertFalse(denied.usageUnconfirmed)
        assertFalse(denied.inFlight)
        assertEquals(0L, denied.accountedTokens)
        assertTrue(denied.receipts.isEmpty())
        assertTrue(delegate.discoveryTimeouts.isEmpty())

        ledger.negotiate(config, Cancellation(), deadline)
        assertEquals(listOf(1000L), delegate.discoveryTimeouts)
        assertEquals(0, delegate.calls)
    }

    @Test fun exceptionWithNoUsageRecordsAnUnconfirmedReceiptAndSafeCauseCode() {
        val delegate = RecordedGateway { _, _ ->
            throw Refused("MODEL_INSECURE_ENDPOINT", "provider", "/secret/path")
        }
        val ledger = meter(delegate)
        assertEquals("MODEL_USAGE_UNCONFIRMED", failureCode { ledger.next() })
        val result = ledger.snapshot()
        assertEquals("MODEL_INSECURE_ENDPOINT", result.causeCode)
        assertEquals(1, result.receipts.size)
        assertEquals(null, result.receipts.single().inputTokens)
        assertEquals(null, result.receipts.single().outputTokens)
        assertEquals("gateway-exception", result.receipts.single().provenance)
        assertEquals(50L, result.accountedTokens)
    }

    @Test fun inFlightSnapshotDoesNotWaitAndConcurrentNextIsRefused() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delegate = RecordedGateway { _, _ ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            reply()
        }
        val ledger = meter(delegate)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit<Any> { ledger.next() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val observation = workers.submit<FailClosedProviderUsageGateway.Snapshot> {
                ledger.snapshot()
            }
            val during = observation.get(1, TimeUnit.SECONDS)
            assertTrue(during.inFlight)
            assertEquals(50L, during.accountedTokens)
            assertEquals(0, during.receipts.size)
            val second = workers.submit<String> {
                failureCode { ledger.next() }
            }
            assertEquals("MODEL_CALL_IN_FLIGHT", second.get(1, TimeUnit.SECONDS))
            assertEquals(1, delegate.calls)
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            assertFalse(ledger.snapshot().inFlight)
            assertEquals(15L, ledger.snapshot().accountedTokens)
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun discoveryDoesNotReserveInferenceBudgetDuringNetworkWait() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delegate = object : ModelGateway {
            override fun negotiate(config: ModelConfig, cancellation: Cancellation,
                deadline: Instant): GatewayCapabilities {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return GatewayCapabilities(config.features, config.contextTokens,
                    config.maxOutputTokens)
            }

            override fun next(config: ModelConfig, task: Path,
                observed: Pair<Int, String>?, cancellation: Cancellation,
                deadline: Instant, allowRetry: Boolean): ModelTurn =
                error("Capability discovery must not trigger inference")
        }
        val ledger = meter(delegate)
        val workers = Executors.newSingleThreadExecutor()
        try {
            val negotiation = workers.submit<GatewayCapabilities> {
                ledger.negotiate(config, Cancellation(), deadline)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val during = ledger.snapshot()
            assertTrue(during.inFlight)
            assertEquals(0L, during.accountedTokens)
            assertEquals(0, during.receipts.size)
            assertEquals("MODEL_CALL_IN_FLIGHT", failureCode { ledger.next() })
            release.countDown()
            negotiation.get(5, TimeUnit.SECONDS)
            assertFalse(ledger.snapshot().inFlight)
            assertEquals(0L, ledger.snapshot().accountedTokens)
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun delegateStoppedPoisonsUsageAndPreservesCancellationKind() {
        for (timedOut in listOf(false, true)) {
            val delegate = RecordedGateway { _, _ -> throw Stopped(timedOut) }
            val ledger = meter(delegate)
            val stopped = assertFailsWith<Stopped> { ledger.next() }
            assertEquals(timedOut, stopped.timedOut)
            val snapshot = ledger.snapshot()
            assertTrue(snapshot.usageUnconfirmed)
            assertEquals(50L, snapshot.accountedTokens)
            assertEquals("MODEL_USAGE_UNCONFIRMED", snapshot.terminalCode)
            assertEquals(1, snapshot.receipts.size)
            assertEquals(FailClosedProviderUsageGateway.ReceiptOutcome.CANCELLED,
                snapshot.receipts.single().outcome)
            assertEquals("gateway-stopped", snapshot.receipts.single().provenance)
            assertEquals(null, snapshot.receipts.single().inputTokens)
            assertEquals(null, snapshot.receipts.single().outputTokens)
            assertEquals("MODEL_USAGE_UNCONFIRMED",
                failureCode { ledger.next() })
            assertEquals(1, delegate.calls)
        }
    }

    @Test fun invalidConfiguredRequestTimeoutStopsBeforeAnyInference() {
        val delegate = RecordedGateway { _, _ -> reply() }
        for (timeout in listOf(0L, -1L)) {
            val ledger = meter(delegate)
            assertEquals("MODEL_REQUEST_TIMEOUT_INVALID",
                failureCode { ledger.next(model = config.copy(requestTimeoutMs = timeout)) })
            assertEquals("MODEL_REQUEST_TIMEOUT_INVALID", ledger.snapshot().terminalCode)
            assertEquals(0L, ledger.snapshot().accountedTokens)
            assertFalse(ledger.snapshot().inFlight)
            assertEquals(0, ledger.snapshot().receipts.size)
        }
        assertEquals(0, delegate.calls)
    }

    @Test fun invalidTimeoutAfterAcceptedTurnRetainsOnlyConfirmedUsage() {
        val delegate = RecordedGateway { _, _ -> reply() }
        val ledger = meter(delegate)
        ledger.next()
        assertEquals("MODEL_REQUEST_TIMEOUT_INVALID",
            failureCode { ledger.next(model = config.copy(requestTimeoutMs = 0)) })
        val snapshot = ledger.snapshot()
        assertEquals(15L, snapshot.accountedTokens)
        assertFalse(snapshot.usageUnconfirmed)
        assertEquals(1, snapshot.receipts.size)
        assertEquals("MODEL_REQUEST_TIMEOUT_INVALID", snapshot.terminalCode)
        assertEquals("MODEL_REQUEST_TIMEOUT_INVALID",
            failureCode { ledger.next() })
        assertEquals(1, delegate.calls)
    }

    @Test fun subMillisecondDeadlineAfterAcceptedTurnDoesNotForfeitGrant() {
        val delegate = RecordedGateway { _, _ -> reply() }
        val ledger = meter(delegate)
        ledger.next()
        now.set(deadline.minusNanos(500_000))
        assertEquals("MODEL_DEADLINE_EXCEEDED",
            failureCode { ledger.next() })
        val snapshot = ledger.snapshot()
        assertEquals(15L, snapshot.accountedTokens)
        assertFalse(snapshot.usageUnconfirmed)
        assertEquals(1, snapshot.receipts.size)
        assertEquals(1, delegate.calls)
    }

    @Test fun cancellationBeforeAnyDispatchDoesNotReserveInferenceTokens() {
        val delegate = RecordedGateway { _, _ -> reply() }
        val cancellation = Cancellation().apply { cancel() }
        val ledger = meter(delegate)
        assertFailsWith<Stopped> {
            ledger.next(config, task, null, cancellation, deadline, false)
        }
        assertEquals(0, delegate.calls)
        assertEquals(0L, ledger.snapshot().accountedTokens)
        assertEquals(0, ledger.snapshot().receipts.size)
    }
}

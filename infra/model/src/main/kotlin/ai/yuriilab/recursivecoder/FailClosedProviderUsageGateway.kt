package ai.yuriilab.recursivecoder

import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Host-owned, single-workflow usage meter over ModelGateway. This adapter is
 * opt-in and NOT wired into installed writable/recursive CLI (FakeModel-only).
 *
 * ModelTurn.usage is accepted only from a trusted host-selected gateway, never
 * model text. Provider-reported usage is not independently authenticated billing.
 * The reservation is in-memory, not a durable parent scheduler debit.
 */
class FailClosedProviderUsageGateway(
    private val delegate: ModelGateway,
    private val grantedTokens: Long,
    private val admittedDeadline: Instant,
    private val clock: Clock = Clock.systemUTC()
) : ModelGateway {
    init { require(grantedTokens > 0) { "Positive host grant required" } }

    enum class ReceiptOutcome {
        ACCEPTED, BUDGET_EXCEEDED, DEADLINE_EXCEEDED, CANCELLED, UNCONFIRMED
    }

    /**
     * Metadata only. Null counters mean no trustworthy observation. In
     * particular, a failed call is not proof of zero provider consumption.
     */
    data class Receipt(
        val ordinal: Int,
        val provider: String,
        val modelId: String,
        val inputTokens: Long?,
        val outputTokens: Long?,
        val statusCode: Int?,
        val attempts: Int?,
        val outcome: ReceiptOutcome,
        val provenance: String
    )

    data class Snapshot(
        val grantedTokens: Long,
        val observedInputTokens: Long,
        val observedOutputTokens: Long,
        val accountedTokens: Long,
        val receipts: List<Receipt>,
        val usageUnconfirmed: Boolean,
        val terminalCode: String?,
        val causeCode: String?,
        val inFlight: Boolean
    )

    private data class Prepared(val config: ModelConfig, val deadline: Instant)

    private enum class Operation { DISCOVERY, COMPLETION }

    private val lock = Any()
    private var inFlightOperation: Operation? = null
    // True only after a completion has been admitted for dispatch, not for
    // discovery or other pre-dispatch policy denials.
    private var completionAttempted = false
    private var input = 0L
    private var output = 0L
    private var unconfirmed = false
    private var terminalCode: String? = null
    private var causeCode: String? = null
    private val receipts = mutableListOf<Receipt>()

    private fun saturatedSum(a: Long, b: Long) =
        if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b

    /** lock must be held */
    private fun observed() = saturatedSum(input, output)

    /**
     * Never waits for a provider call. Only an active completion reserves
     * the grant; capability discovery is not inference. A known overrun
     * accounts the excess, while uncertain completion attempts retain a
     * nonrefundable allocation. No double-counting as a scheduler charge.
     */
    fun snapshot(): Snapshot = synchronized(lock) {
        val observed = observed()
        Snapshot(grantedTokens, input, output,
            if (inFlightOperation == Operation.COMPLETION || unconfirmed ||
                (terminalCode != null && completionAttempted))
                maxOf(grantedTokens, observed)
            else observed,
            receipts.toList(), unconfirmed, terminalCode, causeCode,
            inFlightOperation != null)
    }

    private fun deny(code: String): Nothing =
        throw Refused(code, "policy", "Provider usage grant cannot authorize further work")

    /** lock must be held */
    private fun poison(code: String, providerCode: String? = null): Nothing {
        unconfirmed = true
        terminalCode = code
        if (causeCode == null) causeCode = providerCode
        deny(code)
    }

    /** Uses the same injected clock for every local deadline check. */
    private fun checkCancellation(cancellation: Cancellation, deadline: Instant) {
        val signals = cancellation.signals()
        if (signals.expired || !clock.instant().isBefore(deadline)) throw Stopped(true)
        if (signals.requested) throw Stopped(false)
    }

    /** lock must be held */
    private fun gate(config: ModelConfig, deadline: Instant): Instant {
        if (inFlightOperation != null) deny("MODEL_CALL_IN_FLIGHT")
        terminalCode?.let { deny(it) }
        if (unconfirmed) deny("MODEL_USAGE_UNCONFIRMED")
        // Existing SSE parser accepts usage in arbitrary events; no verified
        // terminal usage provenance exists for streaming.
        if (config.provider != "openai-compatible" ||
            "usage_metadata" !in config.features ||
            "streaming" in config.features || config.maxOutputTokens <= 0)
            deny("MODEL_USAGE_PROVENANCE_UNAVAILABLE")
        val bounded = minOf(deadline, admittedDeadline)
        if (!clock.instant().isBefore(bounded)) deny("MODEL_DEADLINE_EXCEEDED")
        return bounded
    }

    override fun negotiate(
        config: ModelConfig, cancellation: Cancellation, deadline: Instant
    ): GatewayCapabilities {
        val bounded = synchronized(lock) {
            val value = gate(config, deadline)
            checkCancellation(cancellation, value)
            inFlightOperation = Operation.DISCOVERY
            value
        }
        try {
            // Discovery is not an inference attempt; a discovery failure
            // cannot certify successful completion nor spend an inference grant.
            return delegate.negotiate(config, cancellation, bounded)
        } finally {
            synchronized(lock) { inFlightOperation = null }
        }
    }

    private fun record(
        config: ModelConfig, outcome: ReceiptOutcome,
        usage: ModelUsage? = null, status: Int? = null,
        attempts: Int? = null, provenance: String = "host-adapter-response"
    ) {
        // Always invoked with lock held. Invalid counters are not presented
        // as a negative/zero trusted observation.
        receipts.add(Receipt(receipts.size + 1, config.provider, config.modelId,
            usage?.input?.takeIf { it >= 0 },
            usage?.output?.takeIf { it >= 0 },
            status, attempts, outcome, provenance))
    }

    override fun next(
        config: ModelConfig, task: Path, observed: Pair<Int, String>?,
        cancellation: Cancellation, deadline: Instant, allowRetry: Boolean
    ): ModelTurn {
        val prepared = synchronized(lock) {
            val bounded = gate(config, deadline)
            checkCancellation(cancellation, bounded)
            val remaining = grantedTokens - this.observed()
            if (remaining <= 0) {
                terminalCode = "MODEL_TOKEN_BUDGET_EXHAUSTED"
                deny(terminalCode!!)
            }
            val timeout = Duration.between(clock.instant(), bounded).toMillis()
            // No zero- or negative-millisecond request may be dispatched.
            if (timeout <= 0L) {
                terminalCode = "MODEL_DEADLINE_EXCEEDED"
                deny(terminalCode!!)
            }
            val boundedConfig = config.copy(
                maxOutputTokens = minOf(config.maxOutputTokens, remaining),
                requestTimeoutMs = minOf(config.requestTimeoutMs, timeout))
            // Invalid configured timeout is a pre-dispatch configuration
            // error, distinct from expiry of the admitted deadline. No
            // completion reservation/receipt is recorded when nothing ran.
            if (boundedConfig.requestTimeoutMs <= 0L) {
                terminalCode = "MODEL_REQUEST_TIMEOUT_INVALID"
                deny(terminalCode!!)
            }
            completionAttempted = true
            inFlightOperation = Operation.COMPLETION
            Prepared(boundedConfig, bounded)
        }

        // Do NOT hold the monitor across network I/O: audit can observe
        // inFlight, while another completion is explicitly refused.
        val turn = try {
            delegate.next(prepared.config, task, observed, cancellation,
                prepared.deadline, allowRetry = false)
        } catch (stopped: Stopped) {
            synchronized(lock) {
                record(config, ReceiptOutcome.CANCELLED, provenance = "gateway-stopped")
                unconfirmed = true
                terminalCode = "MODEL_USAGE_UNCONFIRMED"
                inFlightOperation = null
            }
            throw stopped
        } catch (interrupted: InterruptedException) {
            synchronized(lock) {
                record(config, ReceiptOutcome.CANCELLED, provenance = "gateway-interrupted")
                unconfirmed = true
                terminalCode = "MODEL_USAGE_UNCONFIRMED"
                inFlightOperation = null
            }
            Thread.currentThread().interrupt()
            throw Stopped(false)
        } catch (failure: Exception) {
            synchronized(lock) {
                record(config, ReceiptOutcome.UNCONFIRMED, provenance = "gateway-exception")
                inFlightOperation = null
                poison("MODEL_USAGE_UNCONFIRMED",
                    (failure as? Refused)?.code)
            }
        } catch (fatal: Error) {
            synchronized(lock) {
                record(config, ReceiptOutcome.UNCONFIRMED, provenance = "gateway-error")
                unconfirmed = true
                terminalCode = "MODEL_USAGE_UNCONFIRMED"
                inFlightOperation = null
            }
            throw fatal
        }

        return synchronized(lock) {
            // A delegate may report some observed usage even after overrun.
            // Retain every representable, nonnegative counter.
            inFlightOperation = null
            val usage = turn.usage
            if (usage == null) {
                record(config, ReceiptOutcome.UNCONFIRMED, status = turn.statusCode,
                    attempts = turn.attempts)
                poison("MODEL_USAGE_UNCONFIRMED")
            }
            val inputOverflow = usage.input >= 0 && usage.input > Long.MAX_VALUE - input
            val outputOverflow = usage.output >= 0 && usage.output > Long.MAX_VALUE - output
            if (usage.input >= 0) input = saturatedSum(input, usage.input)
            if (usage.output >= 0) output = saturatedSum(output, usage.output)
            if (usage.input <= 0 || usage.output < 0 || inputOverflow || outputOverflow ||
                usage.input > Long.MAX_VALUE - usage.output ||
                input > Long.MAX_VALUE - output) {
                record(config, ReceiptOutcome.UNCONFIRMED, usage,
                    turn.statusCode, turn.attempts)
                poison("MODEL_USAGE_UNCONFIRMED")
            }
            val status = turn.statusCode
            if (turn.attempts != 1 || status == null || status !in 200..299) {
                record(config, ReceiptOutcome.UNCONFIRMED, usage,
                    status, turn.attempts)
                poison("MODEL_USAGE_UNCONFIRMED")
            }
            if (usage.output > prepared.config.maxOutputTokens ||
                this.observed() > grantedTokens) {
                record(config, ReceiptOutcome.BUDGET_EXCEEDED, usage,
                    status, turn.attempts)
                terminalCode = "MODEL_TOKEN_BUDGET_EXHAUSTED"
                deny(terminalCode!!)
            }
            if (!clock.instant().isBefore(prepared.deadline)) {
                record(config, ReceiptOutcome.DEADLINE_EXCEEDED, usage,
                    status, turn.attempts)
                terminalCode = "MODEL_DEADLINE_EXCEEDED"
                deny(terminalCode!!)
            }
            // Cancellation after a complete 2xx usage response is terminal,
            // but does not make the reported counters unknown.
            try {
                checkCancellation(cancellation, prepared.deadline)
            } catch (stopped: Stopped) {
                record(config, ReceiptOutcome.CANCELLED, usage,
                    status, turn.attempts)
                terminalCode = "MODEL_CANCELLED_AFTER_RESPONSE"
                throw stopped
            }
            record(config, ReceiptOutcome.ACCEPTED, usage, status, turn.attempts)
            turn
        }
    }
}

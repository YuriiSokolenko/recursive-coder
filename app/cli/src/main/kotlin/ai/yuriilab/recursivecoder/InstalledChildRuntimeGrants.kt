package ai.yuriilab.recursivecoder

import java.time.Clock
import java.time.Instant

/**
 * Host-owned, per-child runtime ledger. A reservation is NOT a measurement.
 *
 * Every role result is checked before its proposal may reach trusted writes;
 * every trusted write/check invocation is counted before dispatch. The same
 * ledger spans all five roles of a Developer-origin child. This is a defensive
 * offline-only gate, not permission to enable a live-model provider.
 */
internal class InstalledChildRuntimeMeter(
    val admitted: RecursiveNode,
    private val clock: Clock = Clock.systemUTC()
) {
    // Physical usage can exceed the admitted grant before a provider returns.
    // Preserve that observation rather than silently truncating it to the grant.
    private var tokens = 0L
    private var tools = 0L
    private var usageUncertain = false

    /** Forensic observation; may exceed the reservation and is not chargeable. */
    @Synchronized fun observed(): RecursiveUsage = RecursiveUsage(tokens, tools)

    /**
     * The policy ledger is bounded by the non-refundable reservation. Any
     * unknown usage, overflow, or failed physical run consumes its full grant.
     * This is a conservative allocation, not fabricated measured consumption.
     */
    @Synchronized fun accounted(failed: Boolean = false): RecursiveUsage =
        if (failed || usageUncertain || tokens > admitted.granted.tokens)
            admitted.granted
        else RecursiveUsage(tokens, tools)

    @Synchronized fun hasUncertainUsage(): Boolean = usageUncertain

    @Synchronized
    fun deadlineDenial(): String? =
        if (!Instant.now(clock).isBefore(admitted.deadline)) "STAGE_DEADLINE" else null

    @Synchronized
    fun beforeModel(): String? = deadlineDenial()
        ?: if (tokens >= admitted.granted.tokens) "TOKEN_BUDGET_EXHAUSTED" else null

    @Synchronized
    fun remainingTokens(): Long = (admitted.granted.tokens - tokens).coerceAtLeast(0L)

    /**
     * The usage fields are supplied by the trusted executor's provider adapter,
     * never by model-generated text. Null/negative counters cannot authorize
     * additional work. Check before any physical writes follow this response.
     */
    @Synchronized
    fun recordRole(usage: StageRoleUsage, proposed: Boolean): String? {
        val input = usage.inputTokens
        val output = usage.outputTokens
        // Keep every non-negative, representable observation even when the
        // model returned after the deadline or consumed past its grant.
        // Missing/invalid counters cannot be treated as exact zero usage.
        if (input != null && input >= 0 && output != null && output >= 0) {
            if (input > Long.MAX_VALUE - output ||
                tokens > Long.MAX_VALUE - input - output) {
                usageUncertain = true
                tokens = Long.MAX_VALUE
            } else {
                tokens += input + output
            }
        } else if (input != null && input >= 0 || output != null && output >= 0) {
            val known = listOfNotNull(input?.takeIf { it >= 0 },
                output?.takeIf { it >= 0 })
            for (value in known) {
                tokens = if (value > Long.MAX_VALUE - tokens)
                    Long.MAX_VALUE else tokens + value
            }
            usageUncertain = true
        }
        if (usage.modelRequests < 0 || input != null && input < 0 ||
            output != null && output < 0 ||
            usage.modelRequests == 0 && (input != null && input != 0L ||
                output != null && output != 0L)) {
            usageUncertain = true
            return "CHILD_USAGE_INVALID"
        }
        if ((proposed && usage.modelRequests == 0) ||
            (usage.modelRequests > 0 && (input == null || output == null))) {
            usageUncertain = true
            return "CHILD_USAGE_UNAVAILABLE"
        }
        if (usageUncertain) return "CHILD_USAGE_INVALID"
        if (tokens > admitted.granted.tokens) return "TOKEN_BUDGET_EXHAUSTED"
        return deadlineDenial()
    }

    /** Attempted invocations are charged, including failed writes and checks. */
    @Synchronized
    fun authorizeToolCall(): String? {
        deadlineDenial()?.let { return it }
        if (tools >= admitted.granted.toolCalls) return "TOOL_BUDGET_EXHAUSTED"
        tools++
        return null
    }

    @Synchronized
    fun measured(): RecursiveUsage = observed()
}

/** The child never receives a parent/global deadline or unbounded output cap. */
internal class MeteredChildRoleExecutor(
    private val delegate: StageRoleExecutor,
    private val meter: InstalledChildRuntimeMeter
) : StageRoleExecutor {
    override suspend fun execute(request: StageRoleRequest,
        events: StageRoleEventSink): StageRoleResult {
        fun denied(code: String): StageRoleResult {
            val stage = request.graph.stages.singleOrNull {
                it.correlation.stageRunId == request.stageRunId
            }
            return StageRoleResult(stage?.correlation, request.expectedRole,
                stage?.agentId, StageRoleOutcome.Denied(code), StageRoleUsage())
        }
        meter.beforeModel()?.let { return denied(it) }
        val bounded = request.copy(
            deadline = minOf(request.deadline, meter.admitted.deadline),
            model = request.model.copy(maxOutputTokens = minOf(
                request.model.maxOutputTokens, meter.remainingTokens()))
        )
        val result = delegate.execute(bounded, events)
        val failure = meter.recordRole(result.usage,
            result.outcome is StageRoleOutcome.Proposed)
        return if (failure == null) result
            else result.copy(outcome = StageRoleOutcome.Denied(failure))
    }
}

/**
 * Only trusted sessions, not model-facing tools, are wrapped. Limits are checked
 * before each write or check. Verify cannot turn an expired grant into success.
 */
internal class MeteredChildTools(
    private val delegate: StageToolSessionOpener,
    private val meter: InstalledChildRuntimeMeter
) : StageToolSessionOpener {
    override fun open(authority: StageToolAuthoritySource): StageToolOpenResult {
        meter.deadlineDenial()?.let { return StageToolOpenResult.Denied(it) }
        val opened = delegate.open(authority)
        if (opened !is StageToolOpenResult.Granted) return opened
        val session = opened.session
        return StageToolOpenResult.Granted(object : StageToolSession {
            override fun write(operations: List<StageWrite>): StageWriteEvidence {
                meter.authorizeToolCall()?.let { return StageWriteEvidence(null, it) }
                val result = session.write(operations)
                val late = meter.deadlineDenial()
                return if (late != null) StageWriteEvidence(null, late) else result
            }

            override fun runCheck(checkId: String): StageCheckEvidence {
                meter.authorizeToolCall()?.let {
                    return StageCheckEvidence(checkId, null, deniedCode = it)
                }
                val result = session.runCheck(checkId)
                val late = meter.deadlineDenial()
                return if (late != null)
                    StageCheckEvidence(checkId, null, deniedCode = late)
                else result
            }

            override fun verify(): StageValidationEvidence {
                val early = meter.deadlineDenial()
                // A verified host snapshot is necessary to construct a typed
                // denial. If identity is missing, fail closed as unconfirmed.
                fun denied(code: String): StageValidationEvidence {
                    val current = authority.current()
                        ?: throw IllegalStateException("CHILD_AUTHORITY_UNCONFIRMED")
                    val stage = current.graph.stages.singleOrNull {
                        it.correlation.stageRunId == current.stageRunId
                    } ?: throw IllegalStateException("CHILD_STAGE_UNCONFIRMED")
                    val actor = stage.agentId
                        ?: throw IllegalStateException("CHILD_ACTOR_UNCONFIRMED")
                    return StageValidationEvidence(stage.correlation, actor,
                        emptyList(), false, code)
                }
                if (early != null) return denied(early)
                val result = session.verify()
                // verify() itself may take longer than the admitted deadline.
                val late = meter.deadlineDenial()
                return if (late == null) result else denied(late)
            }

            override fun close() = session.close()
        })
    }
}

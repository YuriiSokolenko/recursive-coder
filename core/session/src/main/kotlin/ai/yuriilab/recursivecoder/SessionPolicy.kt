package ai.yuriilab.recursivecoder

import java.time.Instant

/** Pure lifecycle decisions. Wire strings are only used when serializing protocol v1. */
internal enum class SessionPhase(val wire: String, val terminal: Boolean = false) {
    CREATED("created"),
    ADMITTED("accepted"),
    RUNNING("running"),
    CANCELLING("cancelled"),
    COMPLETED("succeeded", true),
    REJECTED("rejected", true),
    FAILED("failed", true)
}

internal enum class SessionOutcome(val wire: String) {
    SUCCEEDED("succeeded"),
    REJECTED("rejected"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    TIMED_OUT("timed_out")
}

internal enum class SessionEndReason { SUCCESS, REFUSAL, CANCELLATION, TIMEOUT, FAILURE }

internal sealed interface PhaseDecision {
    data object Allowed : PhaseDecision
    data object Denied : PhaseDecision
}

internal sealed interface FinishDecision {
    data class Completed(val outcome: SessionOutcome, val transitions: List<SessionPhase>) : FinishDecision
    data object Denied : FinishDecision
}

internal object SessionLifecyclePolicy {
    fun transition(from: SessionPhase, to: SessionPhase): PhaseDecision {
        val permitted = when (from) {
            SessionPhase.CREATED -> to == SessionPhase.ADMITTED ||
                to == SessionPhase.REJECTED || to == SessionPhase.FAILED
            SessionPhase.ADMITTED -> to == SessionPhase.RUNNING || to == SessionPhase.FAILED
            SessionPhase.RUNNING -> to == SessionPhase.CANCELLING ||
                to == SessionPhase.COMPLETED || to == SessionPhase.FAILED
            SessionPhase.CANCELLING -> to == SessionPhase.FAILED
            SessionPhase.COMPLETED, SessionPhase.REJECTED, SessionPhase.FAILED -> false
        }
        return if (permitted) PhaseDecision.Allowed else PhaseDecision.Denied
    }

    /** Exactly one terminal resolution; cancellation's intermediary wire event is retained. */
    fun finish(from: SessionPhase, reason: SessionEndReason): FinishDecision {
        if (from.terminal) return FinishDecision.Denied
        return when (reason) {
            SessionEndReason.SUCCESS ->
                if (from == SessionPhase.RUNNING)
                    FinishDecision.Completed(SessionOutcome.SUCCEEDED, listOf(SessionPhase.COMPLETED))
                else FinishDecision.Denied
            SessionEndReason.REFUSAL ->
                if (from == SessionPhase.CREATED)
                    FinishDecision.Completed(SessionOutcome.REJECTED, listOf(SessionPhase.REJECTED))
                else FinishDecision.Completed(SessionOutcome.FAILED, listOf(SessionPhase.FAILED))
            SessionEndReason.CANCELLATION, SessionEndReason.TIMEOUT -> {
                val outcome = if (reason == SessionEndReason.TIMEOUT)
                    SessionOutcome.TIMED_OUT else SessionOutcome.CANCELLED
                val end = if (from == SessionPhase.CREATED) SessionPhase.REJECTED else SessionPhase.FAILED
                val steps = if (reason == SessionEndReason.CANCELLATION && from == SessionPhase.RUNNING)
                    listOf(SessionPhase.CANCELLING, end) else listOf(end)
                FinishDecision.Completed(outcome, steps)
            }
            SessionEndReason.FAILURE ->
                FinishDecision.Completed(SessionOutcome.FAILED, listOf(SessionPhase.FAILED))
        }
    }
}

internal enum class AdmissionDenial(val code: String, val category: String, val message: String) {
    PROTOCOL("UNSUPPORTED_PROTOCOL", "protocol", "Unsupported protocol version"),
    INVOCATION("UNSUPPORTED_INVOCATION", "protocol", "Unsupported invocation source or attempt"),
    RESUME("UNSUPPORTED_RESUME", "checkpoint", "Resume is not supported"),
    CAPABILITY("UNSUPPORTED_CAPABILITY", "protocol", "Required capability is unavailable"),
    MODEL("UNSUPPORTED_MODEL", "provider", "Unsupported model provider or configuration"),
    TOOL_POLICY("UNSUPPORTED_TOOL_POLICY", "policy", "Only read-only task access is supported"),
    BUDGET("INSUFFICIENT_BUDGET", "policy", "Two model turns and one read are required")
}

internal sealed interface AdmissionDecision {
    data object Accepted : AdmissionDecision
    data class Denied(val reason: AdmissionDenial) : AdmissionDecision
}

internal data class SessionAdmissionFacts(
    val supportedProtocol: Boolean,
    val invocationSource: String,
    val attempt: Long,
    val resumeMode: String,
    val fenceGeneration: Long,
    val requiredCapabilities: Set<String>,
    val provider: String,
    val modelId: String,
    val modelFeatures: Set<String>,
    val toolPolicyMode: String,
    val allowedTools: Set<String>,
    val deniedTools: Set<String>,
    val writablePathCount: Int,
    val immutableInputCount: Int,
    val maxToolCalls: Long,
    val maxModelRequests: Long
)

internal object SessionAdmissionPolicy {
    private val capabilities = setOf(
        "session-events-v1", "fake-model-v1", "model-gateway-v1", "read-only-tools-v1"
    )

    fun decide(f: SessionAdmissionFacts): AdmissionDecision {
        fun deny(reason: AdmissionDenial) = AdmissionDecision.Denied(reason)
        if (!f.supportedProtocol) return deny(AdmissionDenial.PROTOCOL)
        if (f.invocationSource !in setOf("cli", "scheduler") ||
            (f.invocationSource == "cli" && f.attempt != 1L))
            return deny(AdmissionDenial.INVOCATION)
        if (f.resumeMode != "fresh" || f.fenceGeneration != 1L)
            return deny(AdmissionDenial.RESUME)
        if (!capabilities.containsAll(f.requiredCapabilities) ||
            "session-events-v1" !in f.requiredCapabilities)
            return deny(AdmissionDenial.CAPABILITY)
        if (f.provider !in setOf("fake", "openai-compatible") ||
            (f.provider == "fake" && (
                "fake-model-v1" !in f.requiredCapabilities ||
                    "model-gateway-v1" in f.requiredCapabilities ||
                    f.modelId != "fake-v1" || f.modelFeatures != setOf("tool_calls")
                )) ||
            (f.provider == "openai-compatible" && (
                "model-gateway-v1" !in f.requiredCapabilities ||
                    "fake-model-v1" in f.requiredCapabilities
                )))
            return deny(AdmissionDenial.MODEL)
        if (f.toolPolicyMode != "read-only" ||
            "read" !in f.allowedTools || "read" in f.deniedTools ||
            f.writablePathCount != 0 || f.immutableInputCount != 0)
            return deny(AdmissionDenial.TOOL_POLICY)
        if (f.maxToolCalls < 1L || f.maxModelRequests < 2L)
            return deny(AdmissionDenial.BUDGET)
        return AdmissionDecision.Accepted
    }
}

internal enum class BudgetDenial(val code: String, val message: String) {
    MODEL_REQUESTS("MODEL_REQUEST_BUDGET_EXCEEDED", "Model request budget exceeded"),
    TOKENS("TOKEN_BUDGET_EXCEEDED", "Model token budget exceeded"),
    TOOLS("TOOL_CALL_BUDGET_EXCEEDED", "Tool call budget exceeded")
}

internal sealed interface BudgetDecision<out T> {
    data class Allowed<T>(val value: T) : BudgetDecision<T>
    data class Denied(val reason: BudgetDenial) : BudgetDecision<Nothing>
}

internal data class SessionBudgetState(
    val modelRequests: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val toolCalls: Long = 0,
    val completeUsage: Boolean = true
)

/** All inputs are values supplied by the coordinator; this policy never reads the clock. */
internal object SessionBudgetPolicy {
    fun effectiveDeadline(started: Instant, requested: Instant, wallTimeMs: Long): Instant =
        minOf(requested, started.plusMillis(wallTimeMs))

    fun deadlineReached(now: Instant, deadline: Instant): Boolean = !now.isBefore(deadline)

    /** Timeout wins when cancellation and deadline expiration race. */
    fun interruption(
        signals: CancellationSignals, now: Instant, deadline: Instant
    ): SessionEndReason? = when {
        signals.expired || deadlineReached(now, deadline) -> SessionEndReason.TIMEOUT
        signals.requested -> SessionEndReason.CANCELLATION
        else -> null
    }

    /** Reserve one actual HTTP POST for each remaining model turn, including confirmation. */
    fun beforeTurn(state: SessionBudgetState, maxRequests: Long, turn: Int): BudgetDecision<Boolean> {
        if (turn !in 0..1 || state.modelRequests < 0 || state.modelRequests > maxRequests)
            return BudgetDecision.Denied(BudgetDenial.MODEL_REQUESTS)
        val remaining = maxRequests - state.modelRequests
        val turnsRemaining = (2 - turn).toLong()
        if (remaining < turnsRemaining) return BudgetDecision.Denied(BudgetDenial.MODEL_REQUESTS)
        return BudgetDecision.Allowed(turn == 0 && remaining > turnsRemaining)
    }

    fun recordModel(
        state: SessionBudgetState,
        maxRequests: Long,
        maxTokens: Long,
        attempts: Int,
        retryAllowed: Boolean,
        usage: ModelUsage?
    ): BudgetDecision<SessionBudgetState> {
        if (state.modelRequests < 0 || state.modelRequests > maxRequests ||
            attempts !in 1..(if (retryAllowed) 2 else 1) ||
            attempts.toLong() > maxRequests - state.modelRequests)
            return BudgetDecision.Denied(BudgetDenial.MODEL_REQUESTS)
        if (state.inputTokens < 0 || state.outputTokens < 0 ||
            state.inputTokens > maxTokens ||
            state.outputTokens > maxTokens - state.inputTokens)
            return BudgetDecision.Denied(BudgetDenial.TOKENS)
        val remaining = maxTokens - state.inputTokens - state.outputTokens
        if (usage != null && (usage.input < 0 || usage.output < 0 ||
                usage.input > remaining || usage.output > remaining - usage.input))
            return BudgetDecision.Denied(BudgetDenial.TOKENS)
        return BudgetDecision.Allowed(state.copy(
            modelRequests = state.modelRequests + attempts,
            inputTokens = state.inputTokens + (usage?.input ?: 0L),
            outputTokens = state.outputTokens + (usage?.output ?: 0L),
            completeUsage = state.completeUsage && usage != null
        ))
    }

    /** Consume quota before dispatching to a trusted workspace port. */
    fun recordTool(state: SessionBudgetState, maxCalls: Long): BudgetDecision<SessionBudgetState> =
        if (state.toolCalls < 0 || state.toolCalls >= maxCalls)
            BudgetDecision.Denied(BudgetDenial.TOOLS)
        else BudgetDecision.Allowed(state.copy(toolCalls = state.toolCalls + 1))
}

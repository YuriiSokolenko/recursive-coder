package ai.yuriilab.recursivecoder

import java.time.Instant
import java.util.Collections

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

/**
 * Supported, immutable adapter API for emitted Session Protocol v1 lifecycle
 * phase wire values. Derived from the internal runtime enum; any vocabulary
 * change requires a compatibility review and an explicit drift-guard update.
 * This is not a public state machine, resume API, or a list of planned phases.
 */
object SessionPhaseVocabulary {
    val createdWire: String = SessionPhase.CREATED.wire
    // Runtime ADMITTED is serialized as "accepted" in Session Protocol v1.
    val acceptedWire: String = SessionPhase.ADMITTED.wire
    val runningWire: String = SessionPhase.RUNNING.wire
    val wires: Set<String> = Collections.unmodifiableSet(
        SessionPhase.entries.mapTo(linkedSetOf()) { it.wire })
    val terminalWires: Set<String> = Collections.unmodifiableSet(
        SessionPhase.entries.filter { it.terminal }
            .mapTo(linkedSetOf()) { it.wire })
}

internal enum class SessionEndReason { SUCCESS, REFUSAL, CANCELLATION, TIMEOUT, FAILURE }

/** Protocol v1 requires a read turn followed by a confirmation turn. */
internal const val SESSION_MODEL_TURNS = 2

/**
 * Public, host-safe projection of the core session interruption precedence.
 * Keeps timeout-before-cancel semantics consistent across Kotlin modules
 * without exposing internal SessionEndReason or session lifecycle state.
 * This is a pure decision and NEVER interrupts or binds a thread.
 */
object SessionInterruptionGate {
    fun stopped(signals: CancellationSignals, now: Instant, deadline: Instant): Stopped? =
        when (SessionBudgetPolicy.interruption(signals, now, deadline)) {
            SessionEndReason.TIMEOUT -> Stopped(true)
            SessionEndReason.CANCELLATION -> Stopped(false)
            else -> null
        }
}

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
    PROTOCOL("UNSUPPORTED_PROTOCOL", SessionDiagnosticCategories.PROTOCOL, "Unsupported protocol version"),
    INVOCATION("UNSUPPORTED_INVOCATION", SessionDiagnosticCategories.PROTOCOL, "Unsupported invocation source or attempt"),
    RESUME(SessionDenialCodes.UNSUPPORTED_RESUME, SessionDiagnosticCategories.CHECKPOINT, "Resume is not supported"),
    CAPABILITY("UNSUPPORTED_CAPABILITY", SessionDiagnosticCategories.PROTOCOL, "Required capability is unavailable"),
    MODEL(ModelDenialCodes.UNSUPPORTED_MODEL, SessionDiagnosticCategories.PROVIDER, "Unsupported model provider or configuration"),
    TOOL_POLICY(ToolDenialCodes.UNSUPPORTED_TOOL_POLICY, SessionDiagnosticCategories.POLICY, "Only read-only task access is supported"),
    BUDGET("INSUFFICIENT_BUDGET", SessionDiagnosticCategories.POLICY, "Two model turns and one read are required")
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
        SessionCapabilityIds.EVENTS_V1, SessionCapabilityIds.FAKE_MODEL_V1, SessionCapabilityIds.MODEL_GATEWAY_V1, SessionCapabilityIds.READ_ONLY_TOOLS_V1
    )

    fun decide(f: SessionAdmissionFacts): AdmissionDecision {
        fun deny(reason: AdmissionDenial) = AdmissionDecision.Denied(reason)
        if (!f.supportedProtocol) return deny(AdmissionDenial.PROTOCOL)
        // Defense in depth: SessionRequest already validates positivity, but pure
        // admission must fail closed even when facts are constructed directly.
        val supportedInvocation = when (f.invocationSource) {
            SessionProtocol.INVOCATION_SOURCE_CLI -> f.attempt == SessionProtocol.FIRST_INVOCATION_ATTEMPT
            SessionProtocol.INVOCATION_SOURCE_SCHEDULER -> f.attempt >= SessionProtocol.FIRST_INVOCATION_ATTEMPT
            else -> false
        }
        if (!supportedInvocation) return deny(AdmissionDenial.INVOCATION)
        if (f.resumeMode != SessionResumeModes.FRESH || f.fenceGeneration != 1L)
            return deny(AdmissionDenial.RESUME)
        if (!capabilities.containsAll(f.requiredCapabilities) ||
            SessionCapabilityIds.EVENTS_V1 !in f.requiredCapabilities)
            return deny(AdmissionDenial.CAPABILITY)
        if (f.provider !in setOf(ModelProviderIds.FAKE, ModelProviderIds.OPENAI_COMPATIBLE) ||
            (f.provider == ModelProviderIds.FAKE && (
                SessionCapabilityIds.FAKE_MODEL_V1 !in f.requiredCapabilities ||
                    SessionCapabilityIds.MODEL_GATEWAY_V1 in f.requiredCapabilities ||
                    f.modelId != ModelProviderIds.FAKE_MODEL_ID || f.modelFeatures != setOf(ModelFeatureIds.TOOL_CALLS)
                )) ||
            (f.provider == ModelProviderIds.OPENAI_COMPATIBLE && (
                SessionCapabilityIds.MODEL_GATEWAY_V1 !in f.requiredCapabilities ||
                    SessionCapabilityIds.FAKE_MODEL_V1 in f.requiredCapabilities ||
                    // Issue #155: without usage_metadata the gateway may return no
                    // usage, and maxTokens could not be enforced. Fail closed.
                    ModelFeatureIds.USAGE_METADATA !in f.modelFeatures
                )))
            return deny(AdmissionDenial.MODEL)
        if (f.toolPolicyMode != ToolPolicyModes.READ_ONLY ||
            WorkspaceToolNames.READ !in f.allowedTools || WorkspaceToolNames.READ in f.deniedTools ||
            f.writablePathCount != 0 || f.immutableInputCount != 0)
            return deny(AdmissionDenial.TOOL_POLICY)
        if (f.maxToolCalls < 1L || f.maxModelRequests < SESSION_MODEL_TURNS.toLong())
            return deny(AdmissionDenial.BUDGET)
        return AdmissionDecision.Accepted
    }
}

/**
 * The protocol v1 order is PROTOCOL -> INVOCATION -> RESUME. Later
 * capabilities, model, tool and budget checks must not pre-empt RESUME.
 * No synthetic "fresh" policy decision is allowed for this probe.
 */
internal object SessionResumePreflightPolicy {
    fun earlierDenial(facts: SessionAdmissionFacts): AdmissionDenial? {
        val decision = SessionAdmissionPolicy.decide(facts)
        if (decision !is AdmissionDecision.Denied) return null
        return decision.reason.takeUnless {
            it == AdmissionDenial.RESUME && facts.resumeMode == SessionResumeModes.RESUME
        }
    }
}

internal enum class BudgetDenial(val code: String, val message: String) {
    MODEL_REQUESTS(ModelDenialCodes.MODEL_REQUEST_BUDGET_EXCEEDED, "Model request budget exceeded"),
    TOKENS(ModelDenialCodes.TOKEN_BUDGET_EXCEEDED, "Model token budget exceeded"),
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

    /**
     * Timeout wins for every sample containing expiration or a reached
     * deadline. CancellationSignals is not an atomic two-flag snapshot.
     */
    fun interruption(
        signals: CancellationSignals, now: Instant, deadline: Instant
    ): SessionEndReason? = when {
        signals.expired || deadlineReached(now, deadline) -> SessionEndReason.TIMEOUT
        signals.requested -> SessionEndReason.CANCELLATION
        else -> null
    }

    /** Reserve one actual HTTP POST for each remaining model turn, including confirmation. */
    fun beforeTurn(state: SessionBudgetState, maxRequests: Long, turn: Int): BudgetDecision<Boolean> {
        if (turn !in 0 until SESSION_MODEL_TURNS ||
            state.modelRequests < 0 || state.modelRequests > maxRequests)
            return BudgetDecision.Denied(BudgetDenial.MODEL_REQUESTS)
        val remaining = maxRequests - state.modelRequests
        val turnsRemaining = (SESSION_MODEL_TURNS - turn).toLong()
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

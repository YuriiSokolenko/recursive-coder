package ai.yuriilab.recursivecoder

import java.time.Instant
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/** These tables are entirely in-memory: no system clock, threads, I/O or adapters. */
@Tag("unit")
class SessionPolicyUnitTest {
    private val created = SessionPhase.CREATED
    private val admitted = SessionPhase.ADMITTED
    private val running = SessionPhase.RUNNING
    private val cancelling = SessionPhase.CANCELLING
    private val completed = SessionPhase.COMPLETED
    private val rejected = SessionPhase.REJECTED
    private val failed = SessionPhase.FAILED

    @Test fun onlyDeclaredLifecycleTransitionsAreLegal() {
        val legal = setOf(
            created to admitted, created to rejected, created to failed,
            admitted to running, admitted to failed,
            running to cancelling, running to completed, running to failed,
            cancelling to failed
        )
        for (from in SessionPhase.entries) {
            for (to in SessionPhase.entries) {
                val decision = SessionLifecyclePolicy.transition(from, to)
                assertEquals(if (from to to in legal) PhaseDecision.Allowed else PhaseDecision.Denied,
                    decision, "$from -> $to")
            }
        }
    }

    @Test fun aTerminalOutcomeCannotBeOverwrittenByCancellationTimeoutOrFailure() {
        for (from in SessionPhase.entries.filter { it.terminal }) {
            for (reason in SessionEndReason.entries) {
                assertEquals(FinishDecision.Denied, SessionLifecyclePolicy.finish(from, reason),
                    "$from / $reason")
            }
        }
        val success = SessionLifecyclePolicy.finish(running, SessionEndReason.SUCCESS)
            as FinishDecision.Completed
        assertEquals(SessionOutcome.SUCCEEDED, success.outcome)
        assertEquals(listOf(completed), success.transitions)
        assertEquals(FinishDecision.Denied,
            SessionLifecyclePolicy.finish(success.transitions.last(), SessionEndReason.TIMEOUT))
        assertEquals(FinishDecision.Denied,
            SessionLifecyclePolicy.finish(success.transitions.last(), SessionEndReason.CANCELLATION))
        assertEquals(FinishDecision.Denied,
            SessionLifecyclePolicy.finish(created, SessionEndReason.SUCCESS))
    }

    @Test fun cancellationTimeoutAndDenialHaveDeterministicPathsFromEveryNonterminalPhase() {
        val nonterminal = listOf(created, admitted, running, cancelling)
        for (from in nonterminal) {
            for (reason in listOf(
                SessionEndReason.REFUSAL,
                SessionEndReason.CANCELLATION,
                SessionEndReason.TIMEOUT,
                SessionEndReason.FAILURE
            )) {
                val result = SessionLifecyclePolicy.finish(from, reason) as FinishDecision.Completed
                val expectedOutcome = when (reason) {
                    SessionEndReason.REFUSAL ->
                        if (from == created) SessionOutcome.REJECTED else SessionOutcome.FAILED
                    SessionEndReason.CANCELLATION -> SessionOutcome.CANCELLED
                    SessionEndReason.TIMEOUT -> SessionOutcome.TIMED_OUT
                    else -> SessionOutcome.FAILED
                }
                assertEquals(expectedOutcome, result.outcome, "$from / $reason")
                val expectedPath = if (reason == SessionEndReason.CANCELLATION && from == running)
                    listOf(cancelling, failed)
                else listOf(if (from == created && reason != SessionEndReason.FAILURE) rejected else failed)
                assertEquals(expectedPath, result.transitions, "$from / $reason")
                var phase = from
                for (next in result.transitions) {
                    assertEquals(PhaseDecision.Allowed, SessionLifecyclePolicy.transition(phase, next))
                    phase = next
                }
                assertTrue(phase.terminal)
                assertEquals(FinishDecision.Denied, SessionLifecyclePolicy.finish(phase, reason))
            }
        }
    }

    @Test fun deadlineTakesPrecedenceOverSimultaneousCancellation() {
        val now = Instant.parse("2026-10-13T09:00:00Z")
        val future = now.plusSeconds(1)
        val expired = now.minusMillis(1)
        val requested = CancellationSignals(requested = true, expired = false)
        assertEquals(SessionEndReason.CANCELLATION,
            SessionBudgetPolicy.interruption(requested, now, future))
        assertEquals(SessionEndReason.TIMEOUT,
            SessionBudgetPolicy.interruption(requested, now, expired))
        assertEquals(SessionEndReason.TIMEOUT,
            SessionBudgetPolicy.interruption(CancellationSignals(true, true), now, future))
        assertEquals(SessionEndReason.TIMEOUT,
            SessionBudgetPolicy.interruption(CancellationSignals(false, true), now, future))
        assertNull(SessionBudgetPolicy.interruption(CancellationSignals(false, false), now, future))
        assertEquals(SessionEndReason.TIMEOUT,
            SessionBudgetPolicy.interruption(CancellationSignals(false, false), future, future))
    }

    private fun validAdmission() = SessionAdmissionFacts(
        supportedProtocol = true,
        invocationSource = "cli",
        attempt = 1,
        resumeMode = "fresh",
        fenceGeneration = 1,
        requiredCapabilities = setOf("session-events-v1", "fake-model-v1", "read-only-tools-v1"),
        provider = "fake",
        modelId = "fake-v1",
        modelFeatures = setOf("tool_calls"),
        toolPolicyMode = "read-only",
        allowedTools = setOf("read"),
        deniedTools = setOf("safe_edit"),
        writablePathCount = 0,
        immutableInputCount = 0,
        maxToolCalls = 1,
        maxModelRequests = 2
    )

    @Test fun admissionDeniesUnsupportedInvocationsCapabilitiesModelAndUnsafePolicies() {
        val valid = validAdmission()
        assertEquals(AdmissionDecision.Accepted, SessionAdmissionPolicy.decide(valid))
        assertEquals(AdmissionDecision.Accepted, SessionAdmissionPolicy.decide(
            valid.copy(invocationSource = "scheduler", attempt = 3)))
        assertEquals(AdmissionDecision.Accepted, SessionAdmissionPolicy.decide(valid.copy(
            provider = "openai-compatible", modelId = "demo",
            modelFeatures = setOf("tool_calls", "usage_metadata"),
            requiredCapabilities = setOf("session-events-v1", "model-gateway-v1", "read-only-tools-v1")
        )))
        val cases = listOf(
            valid.copy(supportedProtocol = false) to AdmissionDenial.PROTOCOL,
            valid.copy(invocationSource = "cli", attempt = 2) to AdmissionDenial.INVOCATION,
            valid.copy(invocationSource = "untrusted") to AdmissionDenial.INVOCATION,
            valid.copy(resumeMode = "resume") to AdmissionDenial.RESUME,
            valid.copy(fenceGeneration = 2) to AdmissionDenial.RESUME,
            valid.copy(requiredCapabilities = setOf("fake-model-v1")) to AdmissionDenial.CAPABILITY,
            valid.copy(requiredCapabilities = valid.requiredCapabilities + "unknown") to AdmissionDenial.CAPABILITY,
            valid.copy(provider = "invalid") to AdmissionDenial.MODEL,
            valid.copy(modelId = "not-fake-v1") to AdmissionDenial.MODEL,
            valid.copy(modelFeatures = setOf("text")) to AdmissionDenial.MODEL,
            valid.copy(toolPolicyMode = "write") to AdmissionDenial.TOOL_POLICY,
            valid.copy(allowedTools = emptySet()) to AdmissionDenial.TOOL_POLICY,
            valid.copy(deniedTools = setOf("read")) to AdmissionDenial.TOOL_POLICY,
            valid.copy(writablePathCount = 1) to AdmissionDenial.TOOL_POLICY,
            valid.copy(immutableInputCount = 1) to AdmissionDenial.TOOL_POLICY,
            valid.copy(maxToolCalls = 0) to AdmissionDenial.BUDGET,
            valid.copy(maxModelRequests = 1) to AdmissionDenial.BUDGET
        )
        for ((facts, denial) in cases) {
            assertEquals(AdmissionDecision.Denied(denial),
                SessionAdmissionPolicy.decide(facts), facts.toString())
        }
    }

    @Test fun modelRequestReservationAndToolQuotaAreFailClosed() {
        val empty = SessionBudgetState()
        assertEquals(BudgetDecision.Allowed(false), SessionBudgetPolicy.beforeTurn(empty, 2, 0))
        assertEquals(BudgetDecision.Allowed(true), SessionBudgetPolicy.beforeTurn(empty, 3, 0))
        assertEquals(BudgetDecision.Denied(BudgetDenial.MODEL_REQUESTS),
            SessionBudgetPolicy.recordModel(empty, 2, 100, 2, false, ModelUsage(1, 1)))
        assertEquals(BudgetDecision.Denied(BudgetDenial.MODEL_REQUESTS),
            SessionBudgetPolicy.recordModel(empty, 3, 100, 3, true, ModelUsage(1, 1)))
        assertEquals(BudgetDecision.Denied(BudgetDenial.MODEL_REQUESTS),
            SessionBudgetPolicy.recordModel(empty, 3, 100, 0, true, null))

        val afterFirst = SessionBudgetPolicy.recordModel(
            empty, 3, 100, 2, true, ModelUsage(4, 5)) as BudgetDecision.Allowed
        assertEquals(2L, afterFirst.value.modelRequests)
        assertEquals(BudgetDecision.Allowed(false),
            SessionBudgetPolicy.beforeTurn(afterFirst.value, 3, 1))
        assertEquals(BudgetDecision.Denied(BudgetDenial.MODEL_REQUESTS),
            SessionBudgetPolicy.recordModel(afterFirst.value, 3, 100, 2, false, null))
        val used = SessionBudgetPolicy.recordTool(empty, 1) as BudgetDecision.Allowed
        assertEquals(1L, used.value.toolCalls)
        assertEquals(BudgetDecision.Denied(BudgetDenial.TOOLS),
            SessionBudgetPolicy.recordTool(used.value, 1))
        assertEquals(BudgetDecision.Denied(BudgetDenial.TOOLS),
            SessionBudgetPolicy.recordTool(empty, 0))
    }

    @Test fun tokenAccountingRejectsOverLimitNegativeAndOverflowWithoutSideEffects() {
        val empty = SessionBudgetState()
        val used = SessionBudgetPolicy.recordModel(
            empty, 2, 10, 1, false, ModelUsage(4, 5)) as BudgetDecision.Allowed
        assertEquals(9L, used.value.inputTokens + used.value.outputTokens)
        assertEquals(BudgetDecision.Denied(BudgetDenial.TOKENS),
            SessionBudgetPolicy.recordModel(used.value, 2, 10, 1, false, ModelUsage(2, 0)))
        for (tokens in listOf(ModelUsage(-1, 2), ModelUsage(1, -1),
            ModelUsage(Long.MAX_VALUE, Long.MAX_VALUE))) {
            assertEquals(BudgetDecision.Denied(BudgetDenial.TOKENS),
                SessionBudgetPolicy.recordModel(empty, 2, 10, 1, false, tokens))
        }
        val unknown = SessionBudgetPolicy.recordModel(
            empty, 2, 10, 1, false, null) as BudgetDecision.Allowed
        assertFalse(unknown.value.completeUsage)
        val second = SessionBudgetPolicy.recordModel(
            unknown.value, 2, 10, 1, false, ModelUsage(1, 1)) as BudgetDecision.Allowed
        assertFalse(second.value.completeUsage)
        assertEquals(2L, second.value.modelRequests)
    }

    @Test fun effectiveDeadlineRespectsTheEarlierOfRequestAndWallBudget() {
        val started = Instant.parse("2026-10-13T09:00:00Z")
        assertEquals(started.plusMillis(100),
            SessionBudgetPolicy.effectiveDeadline(started, started.plusSeconds(10), 100))
        assertEquals(started.plusMillis(50),
            SessionBudgetPolicy.effectiveDeadline(started, started.plusMillis(50), 100))
        assertFalse(SessionBudgetPolicy.deadlineReached(started, started.plusMillis(50)))
        assertTrue(SessionBudgetPolicy.deadlineReached(started.plusMillis(50), started.plusMillis(50)))
    }
}

package ai.yuriilab.recursivecoder

import java.time.Instant
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Issue #37: pure Session admission, quota and interruption boundary tables.
 * Observations and timestamps are explicit; adapters, threads and wall-clock sleeps are absent.
 */
@Tag("unit")
class SessionPolicyCoverageUnitTest {
    private fun admitted() = SessionAdmissionFacts(
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

    @Test fun admissionDenialPrecedenceIsStableWhenMultipleInputsAreInvalid() {
        val good = admitted()
        val cases = listOf(
            Triple("protocol over invocation and resume",
                good.copy(supportedProtocol = false, invocationSource = "unknown",
                    resumeMode = "resume"), AdmissionDenial.PROTOCOL),
            Triple("invocation over resume and capability",
                good.copy(invocationSource = "cli", attempt = 0, resumeMode = "resume",
                    requiredCapabilities = emptySet()), AdmissionDenial.INVOCATION),
            Triple("resume over capability and model",
                good.copy(resumeMode = "resume", requiredCapabilities = emptySet(),
                    provider = "unknown"), AdmissionDenial.RESUME),
            Triple("fence generation over later invalid tool policy",
                good.copy(fenceGeneration = 0, toolPolicyMode = "write"),
                AdmissionDenial.RESUME),
            Triple("capability over model and budget",
                good.copy(requiredCapabilities = setOf("unknown"), provider = "unknown",
                    maxModelRequests = 0), AdmissionDenial.CAPABILITY),
            Triple("model over tool-policy and budget",
                good.copy(provider = "unknown", toolPolicyMode = "write",
                    maxToolCalls = 0), AdmissionDenial.MODEL),
            Triple("tool policy over budget",
                good.copy(deniedTools = setOf("read"), maxModelRequests = 0),
                AdmissionDenial.TOOL_POLICY),
            Triple("budget is last",
                good.copy(maxToolCalls = 0, maxModelRequests = 0), AdmissionDenial.BUDGET)
        )
        assertEquals(AdmissionDecision.Accepted, SessionAdmissionPolicy.decide(good), "Baseline admission")
        for ((name, facts, denial) in cases) {
            assertEquals(AdmissionDecision.Denied(denial),
                SessionAdmissionPolicy.decide(facts), name)
        }
        assertNull(SessionResumePreflightPolicy.earlierDenial(
            good.copy(resumeMode = "resume", requiredCapabilities = emptySet())),
            "A resume probe must stop at RESUME, not fabricate a later capability denial")
    }

    @Test fun admissionBoundariesCoverModelAndToolPolicyVariants() {
        val base = admitted()
        val accepted = listOf(
            base.copy(invocationSource = "scheduler", attempt = 2),
            base.copy(maxToolCalls = Long.MAX_VALUE, maxModelRequests = Long.MAX_VALUE),
            base.copy(provider = "openai-compatible", modelId = "remote",
                requiredCapabilities = setOf("session-events-v1", "model-gateway-v1",
                    "read-only-tools-v1"),
                modelFeatures = setOf("tool_calls", "usage_metadata"))
        )
        accepted.forEachIndexed { index, facts ->
            assertEquals(AdmissionDecision.Accepted, SessionAdmissionPolicy.decide(facts),
                "Accepted admission variant #$index")
        }
        val denied = listOf(
            base.copy(requiredCapabilities = emptySet()) to AdmissionDenial.CAPABILITY,
            base.copy(provider = "fake", modelFeatures = setOf("tool_calls", "extra"))
                to AdmissionDenial.MODEL,
            base.copy(provider = "fake", requiredCapabilities =
                base.requiredCapabilities + "model-gateway-v1") to AdmissionDenial.MODEL,
            base.copy(provider = "openai-compatible", modelId = "remote",
                requiredCapabilities = setOf("session-events-v1", "model-gateway-v1",
                    "read-only-tools-v1"), modelFeatures = setOf("tool_calls"))
                to AdmissionDenial.MODEL,
            base.copy(allowedTools = setOf("safe_edit")) to AdmissionDenial.TOOL_POLICY,
            base.copy(deniedTools = setOf("read")) to AdmissionDenial.TOOL_POLICY,
            base.copy(writablePathCount = -1) to AdmissionDenial.TOOL_POLICY,
            base.copy(immutableInputCount = 1) to AdmissionDenial.TOOL_POLICY,
            base.copy(maxToolCalls = 0) to AdmissionDenial.BUDGET,
            base.copy(maxModelRequests = 1) to AdmissionDenial.BUDGET
        )
        for ((facts, reason) in denied) {
            assertEquals(AdmissionDecision.Denied(reason),
                SessionAdmissionPolicy.decide(facts), "Admission facts: $facts")
        }
    }

    @Test fun schedulerAttemptRangeIsNotValidatedByPureAdmission() {
        // Characterization only: protocol/schema and trusted caller validation are separate.
        // Do not change production admission semantics in this coverage-only issue.
        for (attempt in listOf(-1L, 0L, 1L, 2L, Long.MAX_VALUE)) {
            assertEquals(AdmissionDecision.Accepted,
                SessionAdmissionPolicy.decide(admitted().copy(invocationSource = "scheduler", attempt = attempt)),
                "Current pure scheduler admission for attempt=$attempt")
        }
    }

    @Test fun modelTurnReservationHandlesExactCapacityAndInvalidCounters() {
        data class Case(
            val name: String, val state: SessionBudgetState, val max: Long,
            val turn: Int, val expected: BudgetDecision<Boolean>
        )
        val cases = listOf(
            Case("exactly two turns", SessionBudgetState(), 2, 0,
                BudgetDecision.Allowed(false)),
            Case("one spare request allows retry", SessionBudgetState(), 3, 0,
                BudgetDecision.Allowed(true)),
            Case("confirmation with one left", SessionBudgetState(modelRequests = 1), 2, 1,
                BudgetDecision.Allowed(false)),
            Case("confirmation with none left", SessionBudgetState(modelRequests = 2), 2, 1,
                BudgetDecision.Denied(BudgetDenial.MODEL_REQUESTS)),
            Case("first turn with only one request", SessionBudgetState(), 1, 0,
                BudgetDecision.Denied(BudgetDenial.MODEL_REQUESTS)),
            Case("negative counter", SessionBudgetState(modelRequests = -1), 2, 0,
                BudgetDecision.Denied(BudgetDenial.MODEL_REQUESTS)),
            Case("counter above quota", SessionBudgetState(modelRequests = 3), 2, 0,
                BudgetDecision.Denied(BudgetDenial.MODEL_REQUESTS)),
            Case("negative turn", SessionBudgetState(), 2, -1,
                BudgetDecision.Denied(BudgetDenial.MODEL_REQUESTS)),
            Case("turn after confirmation", SessionBudgetState(), 2, 2,
                BudgetDecision.Denied(BudgetDenial.MODEL_REQUESTS))
        )
        for (case in cases) {
            assertEquals(case.expected,
                SessionBudgetPolicy.beforeTurn(case.state, case.max, case.turn), case.name)
        }
    }

    @Test fun recordedModelUsageRespectsTokenAndRequestEdgesWithoutOverflow() {
        data class Case(
            val name: String, val state: SessionBudgetState, val requestLimit: Long,
            val tokenLimit: Long, val attempts: Int, val retry: Boolean,
            val usage: ModelUsage?, val denial: BudgetDenial?
        )
        val used = SessionBudgetState(modelRequests = 1, inputTokens = 3, outputTokens = 3)
        val cases = listOf(
            Case("exact tokens remaining", used, 2, 10, 1, false,
                ModelUsage(4, 0), null),
            Case("zero usage at exhausted token quota",
                used.copy(inputTokens = 7), 2, 10, 1, false, ModelUsage(0, 0), null),
            Case("one token too many", used, 2, 10, 1, false,
                ModelUsage(5, 0), BudgetDenial.TOKENS),
            Case("combined observations too large", used, 2, 10, 1, false,
                ModelUsage(3, 2), BudgetDenial.TOKENS),
            Case("negative input usage", used, 2, 10, 1, false,
                ModelUsage(-1, 0), BudgetDenial.TOKENS),
            Case("negative output usage", used, 2, 10, 1, false,
                ModelUsage(0, -1), BudgetDenial.TOKENS),
            Case("oversized observation", used, 2, 10, 1, false,
                ModelUsage(Long.MAX_VALUE, 1), BudgetDenial.TOKENS),
            Case("prior negative input counter",
                used.copy(inputTokens = -1), 2, 10, 1, false,
                ModelUsage(0, 0), BudgetDenial.TOKENS),
            Case("prior negative output counter",
                used.copy(outputTokens = -1), 2, 10, 1, false,
                ModelUsage(0, 0), BudgetDenial.TOKENS),
            Case("prior combined token budget exceeded",
                used.copy(outputTokens = 8), 2, 10, 1, false,
                ModelUsage(0, 0), BudgetDenial.TOKENS),
            Case("retry with sufficient model requests", SessionBudgetState(), 2, 10, 2,
                true, ModelUsage(1, 1), null),
            Case("retry requires opt-in", SessionBudgetState(), 2, 10, 2,
                false, ModelUsage(1, 1), BudgetDenial.MODEL_REQUESTS),
            Case("retry cannot exceed request quota", SessionBudgetState(), 1, 10, 2,
                true, ModelUsage(1, 1), BudgetDenial.MODEL_REQUESTS),
            Case("no attempts", SessionBudgetState(), 2, 10, 0, true,
                ModelUsage(1, 1), BudgetDenial.MODEL_REQUESTS),
            Case("request count at limit", used.copy(modelRequests = 2), 2, 10, 1,
                false, ModelUsage(1, 1), BudgetDenial.MODEL_REQUESTS),
            Case("unknown usage is tracked as incomplete", SessionBudgetState(), 2, 10,
                1, false, null, null),
            Case("max long request counter remains bounded",
                SessionBudgetState(modelRequests = Long.MAX_VALUE - 1),
                Long.MAX_VALUE, 10, 1, false, ModelUsage(0, 0), null),
            Case("max long request quota rejects excess",
                SessionBudgetState(modelRequests = Long.MAX_VALUE),
                Long.MAX_VALUE, 10, 1, false, ModelUsage(0, 0),
                BudgetDenial.MODEL_REQUESTS)
        )
        for (case in cases) {
            val result = SessionBudgetPolicy.recordModel(
                case.state, case.requestLimit, case.tokenLimit, case.attempts,
                case.retry, case.usage
            )
            if (case.denial != null) {
                assertEquals(BudgetDecision.Denied(case.denial), result, case.name)
            } else {
                val expected = case.state.copy(
                    modelRequests = case.state.modelRequests + case.attempts,
                    inputTokens = case.state.inputTokens + (case.usage?.input ?: 0L),
                    outputTokens = case.state.outputTokens + (case.usage?.output ?: 0L),
                    completeUsage = case.state.completeUsage && case.usage != null
                )
                assertEquals(BudgetDecision.Allowed(expected), result, case.name)
            }
        }
    }

    @Test fun toolQuotaGuardsZeroNegativeExactAndLongMaxBoundaries() {
        data class Case(val limit: Long, val count: Long, val expectedCount: Long?)
        // null is an explicit denial; successful rows name the resulting counter.
        val cases = listOf(
            Case(0L, -1L, null),
            Case(0L, 0L, null),
            Case(0L, 1L, null),
            Case(0L, 2L, null),
            Case(0L, Long.MAX_VALUE - 1, null),
            Case(0L, Long.MAX_VALUE, null),
            Case(1L, -1L, null),
            Case(1L, 0L, 1L),
            Case(1L, 1L, null),
            Case(1L, 2L, null),
            Case(1L, Long.MAX_VALUE - 1, null),
            Case(1L, Long.MAX_VALUE, null),
            Case(2L, -1L, null),
            Case(2L, 0L, 1L),
            Case(2L, 1L, 2L),
            Case(2L, 2L, null),
            Case(2L, Long.MAX_VALUE - 1, null),
            Case(2L, Long.MAX_VALUE, null),
            Case(Long.MAX_VALUE, -1L, null),
            Case(Long.MAX_VALUE, 0L, 1L),
            Case(Long.MAX_VALUE, 1L, 2L),
            Case(Long.MAX_VALUE, 2L, 3L),
            Case(Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE),
            Case(Long.MAX_VALUE, Long.MAX_VALUE, null)
        )
        for (case in cases) {
            val state = SessionBudgetState(toolCalls = case.count)
            val expected = case.expectedCount?.let {
                BudgetDecision.Allowed(state.copy(toolCalls = it))
            } ?: BudgetDecision.Denied(BudgetDenial.TOOLS)
            assertEquals(expected, SessionBudgetPolicy.recordTool(state, case.limit),
                "toolCalls=${case.count}, maxCalls=${case.limit}")
        }
    }

    @Test fun interruptionMatrixMakesTimeoutDominantAtAndAfterDeadline() {
        val deadline = Instant.parse("2026-11-24T09:00:00Z")
        data class Case(
            val requested: Boolean, val expired: Boolean, val offset: Long,
            val expected: SessionEndReason?
        )
        val cases = listOf(
            Case(false, false, -1, null),
            Case(false, false, 0, SessionEndReason.TIMEOUT),
            Case(false, false, 1, SessionEndReason.TIMEOUT),
            Case(false, true, -1, SessionEndReason.TIMEOUT),
            Case(false, true, 0, SessionEndReason.TIMEOUT),
            Case(false, true, 1, SessionEndReason.TIMEOUT),
            Case(true, false, -1, SessionEndReason.CANCELLATION),
            Case(true, false, 0, SessionEndReason.TIMEOUT),
            Case(true, false, 1, SessionEndReason.TIMEOUT),
            Case(true, true, -1, SessionEndReason.TIMEOUT),
            Case(true, true, 0, SessionEndReason.TIMEOUT),
            Case(true, true, 1, SessionEndReason.TIMEOUT)
        )
        for (case in cases) {
            val signals = CancellationSignals(case.requested, case.expired)
            val now = deadline.plusMillis(case.offset)
            val description = "requested=${case.requested}, expired=${case.expired}, offsetMs=${case.offset}"
            assertEquals(case.expected, SessionBudgetPolicy.interruption(signals, now, deadline),
                description)
            val stopped = SessionInterruptionGate.stopped(signals, now, deadline)
            when (case.expected) {
                null -> assertNull(stopped, "No host interruption: $description")
                SessionEndReason.CANCELLATION ->
                    assertEquals(false, stopped?.timedOut, "Host cancellation: $description")
                SessionEndReason.TIMEOUT ->
                    assertEquals(true, stopped?.timedOut, "Host timeout: $description")
                else -> fail("Unexpected interruption table outcome: ${case.expected}")
            }
        }
        assertEquals(deadline,
            SessionBudgetPolicy.effectiveDeadline(deadline, deadline.plusSeconds(1), 0),
            "Zero wall-time quota must expire immediately")
        assertEquals(deadline.minusMillis(10),
            SessionBudgetPolicy.effectiveDeadline(deadline, deadline.minusMillis(10), 100),
            "Earlier request deadline must win")
    }
}

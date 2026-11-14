package ai.yuriilab.recursivecoder

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("unit")
class InstalledChildRuntimeGrantsTest {
    private val now = Instant.parse("2026-11-14T15:00:00Z")

    private fun meter(
        tokens: Long = 1000L,
        tools: Long = 12L,
        deadline: Instant = now.plusSeconds(60),
        clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    ): InstalledChildRuntimeMeter = InstalledChildRuntimeMeter(
        RecursiveNode(
            graphId = GraphId("grant-test-child"),
            stageRunId = StageRunId("grant-test-stage"),
            parentGraphId = GraphId("grant-test-parent"),
            parentStageRunId = StageRunId("grant-test-parent-stage"),
            requestId = "grant-test-request",
            origin = StageKind.DEVELOP,
            taskId = "grant-test-task",
            dependencies = emptySet(),
            depth = 1,
            writablePaths = setOf("src/main/Feature.kt"),
            frozenPaths = emptySet(),
            allowedReadTools = emptySet(),
            granted = RecursiveUsage(tokens, tools),
            deadline = deadline,
            state = RecursiveNodeState.RUNNING
        ), clock
    )

    @Test fun cumulativeFiveRoleUsageCannotExceedAdmittedTokens() {
        val metered = meter(tokens = 600)
        repeat(4) {
            assertNull(metered.beforeModel())
            assertNull(metered.recordRole(StageRoleUsage(1, 100, 30), true))
        }
        // 4 * 130 fits, but the fifth role must not authorize a proposal.
        assertEquals("TOKEN_BUDGET_EXHAUSTED",
            metered.recordRole(StageRoleUsage(1, 100, 30), true))
        assertEquals(RecursiveUsage(650, 0), metered.measured())
        assertEquals(RecursiveUsage(600, 12), metered.accounted())
        assertEquals(0L, metered.remainingTokens())
    }

    @Test fun nullUnknownNegativeAndOverflowUsageFailClosed() {
        val metered = meter(tokens = 200)
        assertEquals("CHILD_USAGE_UNAVAILABLE",
            metered.recordRole(StageRoleUsage(1, null, 10), true))
        assertEquals("CHILD_USAGE_UNAVAILABLE",
            metered.recordRole(StageRoleUsage(0, null, null), true))
        assertEquals("CHILD_USAGE_INVALID",
            metered.recordRole(StageRoleUsage(1, -1, 2), true))
        assertEquals("TOKEN_BUDGET_EXHAUSTED",
            metered.recordRole(StageRoleUsage(1, Long.MAX_VALUE, 2), true))
        assertEquals(RecursiveUsage(Long.MAX_VALUE, 0), metered.measured())
        assertEquals(RecursiveUsage(200, 12), metered.accounted())
        assertTrue(metered.hasUncertainUsage())
    }

    @Test fun aFailedDelegateWithNoUsageCannotMasqueradeAsZero() {
        val metered = meter(tokens = 250)
        assertEquals("CHILD_USAGE_UNAVAILABLE",
            metered.recordRole(StageRoleUsage(), false))
        assertTrue(metered.hasUncertainUsage())
        assertEquals(RecursiveUsage(250, 12), metered.accounted(failed = true))
    }

    @Test fun trustedToolCallsAreCountedBeforeEveryInvocation() {
        val metered = meter(tools = 3)
        repeat(3) { assertNull(metered.authorizeToolCall()) }
        assertEquals("TOOL_BUDGET_EXHAUSTED", metered.authorizeToolCall())
        assertEquals(RecursiveUsage(0, 3), metered.measured())
    }

    @Test fun deadlineExpiresBeforeModelWriteOrCheckCanStart() {
        val expired = meter(deadline = now)
        assertEquals("STAGE_DEADLINE", expired.beforeModel())
        assertEquals("STAGE_DEADLINE", expired.authorizeToolCall())
        assertEquals("STAGE_DEADLINE",
            expired.recordRole(StageRoleUsage(1, 1, 1), true))
        assertEquals(RecursiveUsage(2, 0), expired.measured())
    }

    @Test fun confirmedUsageAfterDeadlineStillCountsAgainstItsGrant() {
        val expired = meter(tokens = 20, deadline = now)
        assertEquals("STAGE_DEADLINE",
            expired.recordRole(StageRoleUsage(1, 10, 7), true))
        assertEquals(RecursiveUsage(17, 0), expired.observed())
        assertEquals(RecursiveUsage(20, 12), expired.accounted(failed = true))
    }

    @Test fun fakeOnlyModelGateRejectsDifferentProviderAndModelId() {
        val fake = ModelConfig("fake", "fake-v1", "http://localhost/unused",
            "env:UNUSED", 2048, 256, emptySet(), null, 1000, 1000)
        requireInstalledRecursiveFakeModel(fake)
        assertFailsWith<IllegalArgumentException> {
            requireInstalledRecursiveFakeModel(fake.copy(provider = "live"))
        }
        assertFailsWith<IllegalArgumentException> {
            requireInstalledRecursiveFakeModel(fake.copy(modelId = "other"))
        }
    }

    @Test fun wrappedTrustedToolSessionNeverDispatchesOverLimit() {
        val metered = meter(tools = 1)
        var writes = 0
        var checks = 0
        val delegate = StageToolSessionOpener {
            StageToolOpenResult.Granted(object : StageToolSession {
                override fun write(operations: List<StageWrite>): StageWriteEvidence {
                    writes++
                    return StageWriteEvidence("write-1")
                }
                override fun runCheck(checkId: String): StageCheckEvidence {
                    checks++
                    return StageCheckEvidence(checkId, "check-1",
                        policyHash = "policy", manifestHash = "manifest")
                }
                override fun verify(): StageValidationEvidence =
                    error("Not reached in this test")
                override fun close() {}
            })
        }
        val opened = MeteredChildTools(delegate, metered).open(
            StageToolAuthoritySource { null })
        assertTrue(opened is StageToolOpenResult.Granted)
        opened.session.use { session ->
            assertTrue(session.write(listOf(StageWrite.Create(
                "src/main/Feature.kt", "example"))).permitted)
            val denied = session.runCheck("offline-check")
            assertEquals("TOOL_BUDGET_EXHAUSTED", denied.deniedCode)
        }
        assertEquals(1, writes)
        assertEquals(0, checks)
    }
}
